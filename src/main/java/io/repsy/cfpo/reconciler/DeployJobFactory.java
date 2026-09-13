package io.repsy.cfpo.reconciler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.Capabilities;
import io.fabric8.kubernetes.api.model.CapabilitiesBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.repsy.cfpo.cloudflare.CloudflareClient;
import io.repsy.cfpo.config.OperatorConfig;
import io.repsy.cfpo.crd.CloudflarePage;
import io.repsy.cfpo.crd.CloudflarePageSpec;

/**
 * Builds the Job that publishes one version of a site.
 *
 * <p>An init container runs the application image and copies {@code spec.directory} into a shared
 * volume; the main container runs wrangler from the deployer image to upload it. Jobs run in the
 * operator namespace so the API token Secret never has to leave it.
 */
public class DeployJobFactory {

  public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
  public static final String MANAGED_BY_VALUE = "cfpo";
  public static final String MANAGED_SELECTOR = MANAGED_BY_LABEL + "=" + MANAGED_BY_VALUE;
  public static final String OWNER_UID_LABEL = "pages.repsy.io/owner-uid";
  public static final String DEPLOY_HASH_LABEL = "pages.repsy.io/deploy-hash";
  public static final String OWNER_NAMESPACE_ANNOTATION = "pages.repsy.io/owner-namespace";
  public static final String OWNER_NAME_ANNOTATION = "pages.repsy.io/owner-name";
  public static final String IMAGE_ANNOTATION = "pages.repsy.io/image";

  static final String COPY_CONTAINER = "copy-site";
  static final String DEPLOY_CONTAINER = "deploy";
  static final String SITE_VOLUME = "site";
  static final String SITE_PATH = "/cfpo-site";
  static final String DEFAULT_BRANCH = "main";
  static final int BACKOFF_LIMIT = 2;

  /** Arbitrary non-root user for the copy step; static site files are normally world-readable. */
  static final long COPY_UID = 65532L;

  /** The {@code node} user of the deployer image. */
  static final long DEPLOYER_UID = 1000L;

  /** The source directory is passed via $SOURCE_DIR so it is never interpreted by the shell. */
  static final String COPY_SCRIPT =
      """
      set -e
      if [ ! -d "$SOURCE_DIR" ]; then
        echo "directory $SOURCE_DIR does not exist in the image" >&2
        exit 2
      fi
      if [ -z "$(ls -A "$SOURCE_DIR")" ]; then
        echo "directory $SOURCE_DIR is empty" >&2
        exit 3
      fi
      cp -R "$SOURCE_DIR"/. %1$s/
      echo "copied $(find %1$s -type f | wc -l) files from $SOURCE_DIR"
      """
          .formatted(SITE_PATH);

  private final OperatorConfig config;

  public DeployJobFactory(OperatorConfig config) {
    this.config = config;
  }

  public Job build(CloudflarePage page, String projectName, String jobName, String deployHash) {
    CloudflarePageSpec spec = page.getSpec();
    Map<String, String> labels = new HashMap<>();
    labels.put(MANAGED_BY_LABEL, MANAGED_BY_VALUE);
    labels.put(OWNER_UID_LABEL, page.getMetadata().getUid());
    labels.put(DEPLOY_HASH_LABEL, deployHash);

    Map<String, String> annotations = new HashMap<>();
    annotations.put(OWNER_NAMESPACE_ANNOTATION, page.getMetadata().getNamespace());
    annotations.put(OWNER_NAME_ANNOTATION, page.getMetadata().getName());
    annotations.put(IMAGE_ANNOTATION, spec.getImage());

    return new JobBuilder()
        .withNewMetadata()
        .withName(jobName)
        .withNamespace(config.operatorNamespace())
        .withLabels(labels)
        .withAnnotations(annotations)
        .endMetadata()
        .withNewSpec()
        .withBackoffLimit(BACKOFF_LIMIT)
        .withTtlSecondsAfterFinished((int) config.deployJobTtlSeconds())
        .withActiveDeadlineSeconds(config.deployJobDeadlineSeconds())
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(labels)
        .withAnnotations(annotations)
        .endMetadata()
        .withNewSpec()
        .withRestartPolicy("Never")
        .withAutomountServiceAccountToken(false)
        .withEnableServiceLinks(false)
        .withImagePullSecrets(
            config.deployPullSecrets().stream().map(LocalObjectReference::new).toList())
        .withNewSecurityContext()
        .withNewSeccompProfile()
        .withType("RuntimeDefault")
        .endSeccompProfile()
        .endSecurityContext()
        .addNewVolume()
        .withName(SITE_VOLUME)
        .withNewEmptyDir()
        .endEmptyDir()
        .endVolume()
        .addNewInitContainer()
        .withName(COPY_CONTAINER)
        .withImage(spec.getImage())
        // A forced redeploy must not reuse a stale cached copy of a mutable tag.
        .withImagePullPolicy(spec.getRevision() != null ? "Always" : null)
        .withCommand("sh", "-c", COPY_SCRIPT)
        .withEnv(env("SOURCE_DIR", spec.getDirectory()))
        .withSecurityContext(restricted(COPY_UID))
        .withResources(
            new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity("50m"))
                .addToRequests("memory", new Quantity("64Mi"))
                .addToLimits("memory", new Quantity("256Mi"))
                .build())
        .withTerminationMessagePolicy("FallbackToLogsOnError")
        .addNewVolumeMount()
        .withName(SITE_VOLUME)
        .withMountPath(SITE_PATH)
        .endVolumeMount()
        .endInitContainer()
        .addNewContainer()
        .withName(DEPLOY_CONTAINER)
        .withImage(config.deployerImage())
        .withArgs(wranglerArgs(spec, projectName))
        .withEnv(deployerEnv())
        .withSecurityContext(restricted(DEPLOYER_UID))
        .withResources(config.deployJobResources())
        .withTerminationMessagePolicy("FallbackToLogsOnError")
        .addNewVolumeMount()
        .withName(SITE_VOLUME)
        .withMountPath(SITE_PATH)
        .withReadOnly(true)
        .endVolumeMount()
        .endContainer()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build();
  }

  static String branch(CloudflarePageSpec spec) {
    return spec.getBranch() == null || spec.getBranch().isBlank()
        ? DEFAULT_BRANCH
        : spec.getBranch();
  }

  /** Maps a deploy Job back to the CloudflarePage it belongs to, using its annotations. */
  public static Set<ResourceID> ownerOf(Job job) {
    Map<String, String> annotations = job.getMetadata().getAnnotations();
    if (annotations == null) {
      return Set.of();
    }
    String namespace = annotations.get(OWNER_NAMESPACE_ANNOTATION);
    String name = annotations.get(OWNER_NAME_ANNOTATION);
    if (namespace == null || name == null) {
      return Set.of();
    }
    return Set.of(new ResourceID(name, namespace));
  }

  private List<String> wranglerArgs(CloudflarePageSpec spec, String projectName) {
    return List.of(
        "pages",
        "deploy",
        SITE_PATH,
        "--project-name=" + projectName,
        "--branch=" + branch(spec),
        "--commit-message=Deploy " + spec.getImage(),
        "--commit-dirty=true");
  }

  private List<EnvVar> deployerEnv() {
    List<EnvVar> env = new java.util.ArrayList<>();
    env.add(
        new EnvVarBuilder()
            .withName("CLOUDFLARE_API_TOKEN")
            .withNewValueFrom()
            .withNewSecretKeyRef()
            .withName(config.credentialsSecretName())
            .withKey(config.credentialsSecretKey())
            .endSecretKeyRef()
            .endValueFrom()
            .build());
    env.add(env("CLOUDFLARE_ACCOUNT_ID", config.accountId()));
    if (!CloudflareClient.DEFAULT_BASE_URL.equals(config.apiBaseUrl())) {
      env.add(env("CLOUDFLARE_API_BASE_URL", config.apiBaseUrl()));
    }
    return env;
  }

  private static EnvVar env(String name, String value) {
    return new EnvVarBuilder().withName(name).withValue(value).build();
  }

  private static SecurityContext restricted(long uid) {
    Capabilities dropAll = new CapabilitiesBuilder().withDrop("ALL").build();
    return new SecurityContextBuilder()
        .withRunAsNonRoot(true)
        .withRunAsUser(uid)
        .withRunAsGroup(uid)
        .withAllowPrivilegeEscalation(false)
        .withCapabilities(dropAll)
        .build();
  }
}
