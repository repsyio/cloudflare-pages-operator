package io.repsy.cfpo.reconciler;

import static io.repsy.cfpo.crd.CloudflarePageStatus.CONDITION_DEPLOYED;
import static io.repsy.cfpo.crd.CloudflarePageStatus.CONDITION_DNS_CONFIGURED;
import static io.repsy.cfpo.crd.CloudflarePageStatus.CONDITION_DOMAIN_ACTIVE;
import static io.repsy.cfpo.crd.CloudflarePageStatus.CONDITION_READY;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.RetryInfo;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.repsy.cfpo.cloudflare.CloudflareApiException;
import io.repsy.cfpo.cloudflare.CloudflareClient;
import io.repsy.cfpo.cloudflare.model.DnsRecord;
import io.repsy.cfpo.cloudflare.model.PagesDomain;
import io.repsy.cfpo.cloudflare.model.PagesProject;
import io.repsy.cfpo.cloudflare.model.Zone;
import io.repsy.cfpo.config.OperatorConfig;
import io.repsy.cfpo.crd.CloudflarePage;
import io.repsy.cfpo.crd.CloudflarePageSpec;
import io.repsy.cfpo.crd.CloudflarePageStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a CloudflarePage to its desired state: Pages project exists, the current image content is
 * deployed, the custom domain is attached and its CNAME record points at the project.
 */
@ControllerConfiguration(
    name = CloudflarePageReconciler.NAME,
    finalizerName = CloudflarePageReconciler.FINALIZER)
public class CloudflarePageReconciler
    implements Reconciler<CloudflarePage>, Cleaner<CloudflarePage> {

  public static final String NAME = "cloudflarepage";
  public static final String FINALIZER = "pages.repsy.io/finalizer";
  public static final String KEEP_ON_DELETE_ANNOTATION = "pages.repsy.io/keep-on-delete";

  static final String JOB_EVENT_SOURCE = "deploy-jobs";
  static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(30);
  static final Duration FAILURE_INTERVAL = Duration.ofMinutes(5);
  private static final int HTTP_CONFLICT = 409;

  private static final Set<String> FAILURE_REASONS =
      Set.of("DeployFailed", "DomainRejected", "DomainError", "DnsConflict");

  private static final Logger LOG = LoggerFactory.getLogger(CloudflarePageReconciler.class);

  private final CloudflareClient cloudflare;
  private final OperatorConfig config;
  private final DeployJobFactory jobFactory;

  public CloudflarePageReconciler(
      final CloudflareClient cloudflare,
      final OperatorConfig config,
      final DeployJobFactory jobFactory) {
    this.cloudflare = cloudflare;
    this.config = config;
    this.jobFactory = jobFactory;
  }

  @Override
  public List<EventSource<?, CloudflarePage>> prepareEventSources(
      final EventSourceContext<CloudflarePage> context) {
    final InformerEventSourceConfiguration<Job> jobs =
        InformerEventSourceConfiguration.from(Job.class, CloudflarePage.class)
            .withName(JOB_EVENT_SOURCE)
            .withNamespaces(this.config.operatorNamespace())
            .withLabelSelector(DeployJobFactory.MANAGED_SELECTOR)
            .withSecondaryToPrimaryMapper(DeployJobFactory::ownerOf)
            .build();
    return List.of(new InformerEventSource<>(jobs));
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  @Override
  public UpdateControl<CloudflarePage> reconcile(
      final CloudflarePage page, final Context<CloudflarePage> context) {
    final CloudflarePageSpec spec = page.getSpec();
    final CloudflarePageStatus status = statusOf(page);
    final Long generation = page.getMetadata().getGeneration();
    final String domain = spec.getDomain().toLowerCase(Locale.ROOT);
    status.setObservedGeneration(generation);
    status.setUrl("https://" + domain);

    final String projectName =
        Naming.projectName(
            spec.getProjectName(), page.getMetadata().getNamespace(), page.getMetadata().getName());
    if (status.getProjectName() != null && !status.getProjectName().equals(projectName)) {
      return this.finish(
          page,
          CloudflarePageStatus.PHASE_FAILED,
          FAILURE_INTERVAL,
          ("projectName cannot change after the project was created (current: %s); "
                  + "recreate the resource to use another project")
              .formatted(status.getProjectName()));
    }

    final PagesProject project = this.ensureProject(page, projectName, context);
    final String deployHash = this.ensureDeployed(page, projectName, context);
    final Optional<PagesDomain> attached = this.ensureDomain(page, projectName, domain, context);
    final boolean dnsConflict =
        attached.isPresent() && this.ensureDns(page, domain, pagesDevHost(project), context);

    final boolean deployed = deployHash.equals(status.getDeployedHash());
    final boolean deployFailed = deployHash.equals(status.getFailedHash());
    final boolean domainActive = attached.map(PagesDomain::isActive).orElse(false);
    final boolean domainFailed = attached.isEmpty() || attached.get().isFailed();

    if (deployFailed || domainFailed || dnsConflict) {
      return this.finish(page, CloudflarePageStatus.PHASE_FAILED, FAILURE_INTERVAL);
    }
    if (deployed && domainActive) {
      return this.finish(page, CloudflarePageStatus.PHASE_READY, this.config.resyncInterval());
    }
    return this.finish(
        page,
        deployed ? CloudflarePageStatus.PHASE_PENDING : CloudflarePageStatus.PHASE_DEPLOYING,
        PROGRESS_INTERVAL);
  }

  @Override
  public ErrorStatusUpdateControl<CloudflarePage> updateErrorStatus(
      final CloudflarePage page, final Context<CloudflarePage> context, final Exception e) {
    final CloudflarePageStatus status = statusOf(page);
    status.setMessage(e.getMessage());
    status.setCondition(
        CONDITION_READY,
        false,
        "ReconcileError",
        e.getMessage(),
        page.getMetadata().getGeneration());

    final boolean permanent = e instanceof CloudflareApiException cf && !cf.isTransient();
    final boolean lastAttempt = context.getRetryInfo().map(RetryInfo::isLastAttempt).orElse(false);
    if (permanent || lastAttempt) {
      LOG.warn("Reconciling {} failed: {}", key(page), e.getMessage());
      status.setPhase(CloudflarePageStatus.PHASE_FAILED);
      return ErrorStatusUpdateControl.patchStatus(page)
          .withNoRetry()
          .rescheduleAfter(FAILURE_INTERVAL);
    }
    return ErrorStatusUpdateControl.patchStatus(page);
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  @Override
  public DeleteControl cleanup(final CloudflarePage page, final Context<CloudflarePage> context) {
    this.deleteDeployJobs(page, context);

    final String keep = annotation(page, KEEP_ON_DELETE_ANNOTATION);
    final CloudflarePageStatus status = page.getStatus();
    if ("true".equalsIgnoreCase(keep)) {
      LOG.info(
          "{} deleted with {}=true, leaving Cloudflare resources in place",
          key(page),
          KEEP_ON_DELETE_ANNOTATION);
      return DeleteControl.defaultDelete();
    }
    if (status == null || status.getProjectName() == null) {
      return DeleteControl.defaultDelete();
    }

    try {
      if (status.getDomain() != null) {
        this.deleteOwnDnsRecords(page, status.getDomain());
        this.cloudflare.deleteDomain(status.getProjectName(), status.getDomain());
        LOG.info("Removed domain {} from project {}", status.getDomain(), status.getProjectName());
      }
      if (Boolean.TRUE.equals(status.getProjectCreated())) {
        this.cloudflare.deleteProject(status.getProjectName());
        LOG.info("Deleted Cloudflare Pages project {}", status.getProjectName());
      }
    } catch (final CloudflareApiException e) {
      if (e.isTransient()) {
        throw e;
      }
      final String message =
          e.getMessage()
              + ". Fix the problem in Cloudflare, or set the annotation "
              + KEEP_ON_DELETE_ANNOTATION
              + "=true to delete this resource without cleaning up Cloudflare.";
      LOG.warn("Cleanup of {} failed: {}", key(page), message);
      context.eventRecorder().warn("CleanupFailed", message);
      return DeleteControl.noFinalizerRemoval().rescheduleAfter(FAILURE_INTERVAL.toMillis());
    }
    return DeleteControl.defaultDelete();
  }

  // ---- steps ----

  private PagesProject ensureProject(
      final CloudflarePage page, final String projectName, final Context<CloudflarePage> context) {
    final CloudflarePageStatus status = page.getStatus();
    final Optional<PagesProject> existing = this.cloudflare.getProject(projectName);
    final PagesProject project;
    if (existing.isPresent()) {
      project = existing.get();
      if (status.getProjectName() == null) {
        status.setProjectCreated(false);
        context
            .eventRecorder()
            .normal(
                "ProjectAdopted",
                "Using existing Cloudflare Pages project "
                    + projectName
                    + "; it will not be deleted together with this resource");
      }
    } else {
      project = this.cloudflare.createProject(projectName, DeployJobFactory.branch(page.getSpec()));
      status.setProjectCreated(true);
      LOG.info("Created Cloudflare Pages project {} for {}", projectName, key(page));
      context
          .eventRecorder()
          .normal("ProjectCreated", "Created Cloudflare Pages project " + projectName);
    }
    status.setProjectName(projectName);
    status.setPagesDevUrl("https://" + pagesDevHost(project));
    return project;
  }

  /** Makes sure the current image content is (being) deployed; returns the deploy hash. */
  private String ensureDeployed(
      final CloudflarePage page, final String projectName, final Context<CloudflarePage> context) {
    final CloudflarePageSpec spec = page.getSpec();
    final CloudflarePageStatus status = page.getStatus();
    final Long generation = page.getMetadata().getGeneration();
    final String hash = Naming.deployHash(deploySource(spec), spec.getDirectory());

    if (hash.equals(status.getDeployedHash())) {
      status.setCondition(
          CONDITION_DEPLOYED,
          true,
          "Deployed",
          "Deployed " + status.getDeployedImage(),
          generation);
      return hash;
    }
    if (hash.equals(status.getFailedHash())) {
      return hash;
    }

    final String namespace = page.getMetadata().getNamespace();
    final String jobName = Naming.deployJobName(namespace, page.getMetadata().getName(), hash);
    this.stopSupersededJobs(jobName, context);

    final Optional<Job> job =
        context.getSecondaryResource(
            Job.class, JOB_EVENT_SOURCE, jobName, this.config.operatorNamespace());
    if (job.isEmpty()) {
      this.createJob(this.jobFactory.build(page, projectName, jobName, hash), context);
      status.setDeployJob(jobName);
      final String message =
          "Deploying %s with job %s/%s"
              .formatted(spec.getImage(), this.config.operatorNamespace(), jobName);
      status.setCondition(CONDITION_DEPLOYED, false, "Deploying", message, generation);
      context.eventRecorder().normal("DeployStarted", message);
    } else if (JobStates.succeeded(job.get())) {
      status.setDeployedHash(hash);
      status.setDeployedImage(spec.getImage());
      status.setDeployedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
      status.setFailedHash(null);
      status.setCondition(
          CONDITION_DEPLOYED, true, "Deployed", "Deployed " + spec.getImage(), generation);
      LOG.info("Deployed {} for {}", spec.getImage(), key(page));
      context
          .eventRecorder()
          .normal("Deployed", "Deployed " + spec.getImage() + " to project " + projectName);
    } else if (JobStates.failed(job.get())) {
      final String reason = JobStates.describeFailure(job.get(), this.podsOf(jobName, context));
      status.setFailedHash(hash);
      status.setCondition(CONDITION_DEPLOYED, false, "DeployFailed", reason, generation);
      LOG.warn("Deploy of {} for {} failed: {}", spec.getImage(), key(page), reason);
      context.eventRecorder().warn("DeployFailed", reason);
    } else {
      status.setCondition(
          CONDITION_DEPLOYED,
          false,
          "Deploying",
          "Deploy job " + jobName + " is running",
          generation);
    }
    return hash;
  }

  /** Attaches the custom domain; returns empty if Cloudflare rejected it. */
  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  private Optional<PagesDomain> ensureDomain(
      final CloudflarePage page,
      final String projectName,
      final String domain,
      final Context<CloudflarePage> context) {
    final CloudflarePageStatus status = page.getStatus();
    final Long generation = page.getMetadata().getGeneration();

    if (status.getDomain() != null && !status.getDomain().equals(domain)) {
      final String previous = status.getDomain();
      this.deleteOwnDnsRecords(page, previous);
      this.cloudflare.deleteDomain(projectName, previous);
      status.setDomain(null);
      status.setDomainStatus(null);
      status.setZoneId(null);
      status.setDnsRecordId(null);
      context.eventRecorder().normal("DomainDetached", "Detached previous domain " + previous);
    }

    final PagesDomain attached;
    try {
      final Optional<PagesDomain> existing =
          this.cloudflare.listDomains(projectName).stream()
              .filter(d -> domain.equalsIgnoreCase(d.name()))
              .findFirst();
      if (existing.isPresent()) {
        attached = existing.get();
      } else {
        attached = this.cloudflare.addDomain(projectName, domain);
        context
            .eventRecorder()
            .normal("DomainAdded", "Added custom domain " + domain + " to project " + projectName);
      }
    } catch (final CloudflareApiException e) {
      if (e.isTransient()) {
        throw e;
      }
      status.setCondition(
          CONDITION_DOMAIN_ACTIVE, false, "DomainRejected", e.getMessage(), generation);
      return Optional.empty();
    }

    status.setDomain(domain);
    status.setDomainStatus(attached.status());
    if (attached.isActive()) {
      status.setCondition(
          CONDITION_DOMAIN_ACTIVE, true, "Active", domain + " is active", generation);
    } else if (attached.isFailed()) {
      final String detail = attached.errorMessage() != null ? ": " + attached.errorMessage() : "";
      status.setCondition(
          CONDITION_DOMAIN_ACTIVE,
          false,
          "DomainError",
          "Domain status is " + attached.status() + detail,
          generation);
    } else {
      status.setCondition(
          CONDITION_DOMAIN_ACTIVE,
          false,
          "Pending",
          "Cloudflare is validating " + domain + " (status: " + attached.status() + ")",
          generation);
    }
    return Optional.of(attached);
  }

  /** Points a proxied CNAME at the project; returns true if a foreign record blocks it. */
  private boolean ensureDns(
      final CloudflarePage page,
      final String domain,
      final String target,
      final Context<CloudflarePage> context) {
    final CloudflarePageStatus status = page.getStatus();
    final Long generation = page.getMetadata().getGeneration();

    final Optional<Zone> zone = this.cloudflare.findZoneForDomain(domain);
    if (zone.isEmpty()) {
      status.setZoneId(null);
      status.setDnsRecordId(null);
      status.setCondition(
          CONDITION_DNS_CONFIGURED,
          false,
          "ZoneNotFound",
          "No zone for %s in the Cloudflare account; create a CNAME record %s -> %s with your DNS provider"
              .formatted(domain, domain, target),
          generation);
      return false;
    }

    final String zoneId = zone.get().id();
    final String comment = dnsComment(page);
    final List<DnsRecord> records = this.cloudflare.listRecordsByName(zoneId, domain);
    final Optional<DnsRecord> own =
        records.stream().filter(r -> isOwnRecord(r, comment)).findFirst();

    DnsRecord record;
    if (own.isPresent()) {
      record = own.get();
      if (!target.equalsIgnoreCase(record.content()) || !Boolean.TRUE.equals(record.proxied())) {
        record = this.cloudflare.updateCnameRecord(zoneId, record.id(), target, comment);
      }
    } else if (records.isEmpty()) {
      record = this.cloudflare.createCnameRecord(zoneId, domain, target, comment);
      context
          .eventRecorder()
          .normal("DnsRecordCreated", "Created CNAME " + domain + " -> " + target);
    } else {
      final String existing =
          records.stream().map(r -> r.type() + " " + r.content()).collect(Collectors.joining(", "));
      status.setCondition(
          CONDITION_DNS_CONFIGURED,
          false,
          "DnsConflict",
          ("DNS records for %s already exist and are not managed by cfpo (%s); "
                  + "remove them to let cfpo create a CNAME to %s")
              .formatted(domain, existing, target),
          generation);
      return true;
    }

    status.setZoneId(zoneId);
    status.setDnsRecordId(record.id());
    status.setCondition(
        CONDITION_DNS_CONFIGURED,
        true,
        "Configured",
        "CNAME " + domain + " -> " + target,
        generation);
    return false;
  }

  // ---- helpers ----

  private UpdateControl<CloudflarePage> finish(
      final CloudflarePage page, final String phase, final Duration reschedule) {
    final CloudflarePageStatus status = page.getStatus();
    final String message =
        CloudflarePageStatus.PHASE_READY.equals(phase)
            ? "Serving " + status.getUrl()
            : firstProblem(status);
    return this.finish(page, phase, reschedule, message);
  }

  private UpdateControl<CloudflarePage> finish(
      final CloudflarePage page,
      final String phase,
      final Duration reschedule,
      final String message) {
    final CloudflarePageStatus status = page.getStatus();
    status.setPhase(phase);
    status.setMessage(message);
    status.setCondition(
        CONDITION_READY,
        CloudflarePageStatus.PHASE_READY.equals(phase),
        phase,
        message,
        page.getMetadata().getGeneration());
    return UpdateControl.patchStatus(page).rescheduleAfter(reschedule);
  }

  /** The most relevant unmet condition: hard failures first, then in reconciliation order. */
  private static String firstProblem(final CloudflarePageStatus status) {
    final List<Condition> unmet =
        Stream.of(CONDITION_DEPLOYED, CONDITION_DOMAIN_ACTIVE, CONDITION_DNS_CONFIGURED)
            .map(status::condition)
            .flatMap(Optional::stream)
            .filter(c -> !"True".equals(c.getStatus()))
            .toList();
    return unmet.stream()
        .filter(c -> FAILURE_REASONS.contains(c.getReason()))
        .findFirst()
        .or(() -> unmet.stream().findFirst())
        .map(Condition::getMessage)
        .orElse(null);
  }

  private void createJob(final Job job, final Context<CloudflarePage> context) {
    try {
      context.getClient().resource(job).create();
      LOG.info(
          "Created deploy job {}/{}",
          job.getMetadata().getNamespace(),
          job.getMetadata().getName());
    } catch (final KubernetesClientException e) {
      // The informer cache may not have caught up with a Job created by a previous reconciliation.
      if (e.getCode() != HTTP_CONFLICT) {
        throw e;
      }
    }
  }

  /** A newer spec supersedes running deploys, so an older upload cannot finish last. */
  private void stopSupersededJobs(
      final String currentJobName, final Context<CloudflarePage> context) {
    // Not the (type, eventSourceName) overload: for an informer it lists the cache in the primary's
    // namespace, and deploy Jobs live in the operator namespace. This one goes through the
    // owner-annotation index, and deploy-jobs is the only Job event source.
    context
        .getSecondaryResourcesAsStream(Job.class)
        .filter(j -> !currentJobName.equals(j.getMetadata().getName()))
        .filter(j -> !JobStates.finished(j))
        .forEach(
            j -> {
              LOG.info("Stopping superseded deploy job {}", j.getMetadata().getName());
              context
                  .getClient()
                  .batch()
                  .v1()
                  .jobs()
                  .inNamespace(j.getMetadata().getNamespace())
                  .withName(j.getMetadata().getName())
                  .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                  .delete();
            });
  }

  private void deleteDeployJobs(final CloudflarePage page, final Context<CloudflarePage> context) {
    context
        .getClient()
        .batch()
        .v1()
        .jobs()
        .inNamespace(this.config.operatorNamespace())
        .withLabel(DeployJobFactory.OWNER_UID_LABEL, page.getMetadata().getUid())
        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
        .delete();
  }

  private List<Pod> podsOf(final String jobName, final Context<CloudflarePage> context) {
    return context
        .getClient()
        .pods()
        .inNamespace(this.config.operatorNamespace())
        .withLabel("job-name", jobName)
        .list()
        .getItems();
  }

  private void deleteOwnDnsRecords(final CloudflarePage page, final String domain) {
    final String comment = dnsComment(page);
    this.cloudflare
        .findZoneForDomain(domain)
        .ifPresent(
            zone ->
                this.cloudflare.listRecordsByName(zone.id(), domain).stream()
                    .filter(r -> isOwnRecord(r, comment))
                    .forEach(
                        r -> {
                          this.cloudflare.deleteDnsRecord(zone.id(), r.id());
                          LOG.info("Deleted DNS record {} ({})", domain, r.id());
                        }));
  }

  private static boolean isOwnRecord(final DnsRecord record, final String comment) {
    return "CNAME".equals(record.type()) && comment.equals(record.comment());
  }

  private static String dnsComment(final CloudflarePage page) {
    return Naming.dnsComment(page.getMetadata().getNamespace(), page.getMetadata().getName());
  }

  private static String pagesDevHost(final PagesProject project) {
    return project.subdomain() != null ? project.subdomain() : Naming.pagesDevHost(project.name());
  }

  /** The image plus the optional revision, so bumping the revision forces a new deploy. */
  private static String deploySource(final CloudflarePageSpec spec) {
    return spec.getRevision() == null
        ? spec.getImage()
        : spec.getImage() + "#" + spec.getRevision();
  }

  private static CloudflarePageStatus statusOf(final CloudflarePage page) {
    if (page.getStatus() == null) {
      page.setStatus(new CloudflarePageStatus());
    }
    return page.getStatus();
  }

  private static String annotation(final CloudflarePage page, final String key) {
    return page.getMetadata().getAnnotations() == null
        ? null
        : page.getMetadata().getAnnotations().get(key);
  }

  private static String key(final CloudflarePage page) {
    return page.getMetadata().getNamespace() + "/" + page.getMetadata().getName();
  }
}
