package io.repsy.cfpo.crd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.crd.generator.annotation.PrinterColumn;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudflarePageStatus {

  public static final String PHASE_PENDING = "Pending";
  public static final String PHASE_DEPLOYING = "Deploying";
  public static final String PHASE_READY = "Ready";
  public static final String PHASE_FAILED = "Failed";

  public static final String CONDITION_READY = "Ready";
  public static final String CONDITION_DEPLOYED = "Deployed";
  public static final String CONDITION_DOMAIN_ACTIVE = "DomainActive";
  public static final String CONDITION_DNS_CONFIGURED = "DnsConfigured";

  @PrinterColumn(name = "Phase")
  private String phase;

  @PrinterColumn(name = "Message", priority = 1)
  private String message;

  private Long observedGeneration;

  @PrinterColumn(name = "URL")
  private String url;

  private String pagesDevUrl;
  private String projectName;

  /** Whether the operator created the Pages project, and is therefore allowed to delete it. */
  private Boolean projectCreated;

  /** The custom domain currently attached to the project by the operator. */
  private String domain;

  private String domainStatus;
  private String zoneId;
  private String dnsRecordId;
  private String deployJob;
  private String deployedHash;
  private String deployedImage;
  private String deployedAt;

  /** Deploy hash whose Job failed; it is not retried until the spec changes. */
  private String failedHash;

  private List<Condition> conditions = new ArrayList<>();

  /** Adds or updates a condition, keeping its transition time when the status did not change. */
  public void setCondition(
      final String type,
      final boolean value,
      final String reason,
      final String conditionMessage,
      final Long generation) {
    final String statusValue = value ? "True" : "False";
    final Optional<Condition> existing = this.condition(type);
    if (existing.isEmpty()) {
      this.conditions.add(
          new ConditionBuilder()
              .withType(type)
              .withStatus(statusValue)
              .withReason(reason)
              .withMessage(conditionMessage)
              .withObservedGeneration(generation)
              .withLastTransitionTime(now())
              .build());
      return;
    }
    final Condition condition = existing.get();
    if (!statusValue.equals(condition.getStatus())) {
      condition.setLastTransitionTime(now());
    }
    condition.setStatus(statusValue);
    condition.setReason(reason);
    condition.setMessage(conditionMessage);
    condition.setObservedGeneration(generation);
  }

  public Optional<Condition> condition(final String type) {
    return this.conditions.stream().filter(c -> type.equals(c.getType())).findFirst();
  }

  @JsonIgnore
  public boolean isConditionTrue(final String type) {
    return this.condition(type).map(c -> "True".equals(c.getStatus())).orElse(false);
  }

  private static String now() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  public String getPhase() {
    return this.phase;
  }

  public void setPhase(final String phase) {
    this.phase = phase;
  }

  public String getMessage() {
    return this.message;
  }

  public void setMessage(final String message) {
    this.message = message;
  }

  public Long getObservedGeneration() {
    return this.observedGeneration;
  }

  public void setObservedGeneration(final Long observedGeneration) {
    this.observedGeneration = observedGeneration;
  }

  public String getUrl() {
    return this.url;
  }

  public void setUrl(final String url) {
    this.url = url;
  }

  public String getPagesDevUrl() {
    return this.pagesDevUrl;
  }

  public void setPagesDevUrl(final String pagesDevUrl) {
    this.pagesDevUrl = pagesDevUrl;
  }

  public String getProjectName() {
    return this.projectName;
  }

  public void setProjectName(final String projectName) {
    this.projectName = projectName;
  }

  public Boolean getProjectCreated() {
    return this.projectCreated;
  }

  public void setProjectCreated(final Boolean projectCreated) {
    this.projectCreated = projectCreated;
  }

  public String getDomain() {
    return this.domain;
  }

  public void setDomain(final String domain) {
    this.domain = domain;
  }

  public String getDomainStatus() {
    return this.domainStatus;
  }

  public void setDomainStatus(final String domainStatus) {
    this.domainStatus = domainStatus;
  }

  public String getZoneId() {
    return this.zoneId;
  }

  public void setZoneId(final String zoneId) {
    this.zoneId = zoneId;
  }

  public String getDnsRecordId() {
    return this.dnsRecordId;
  }

  public void setDnsRecordId(final String dnsRecordId) {
    this.dnsRecordId = dnsRecordId;
  }

  public String getDeployJob() {
    return this.deployJob;
  }

  public void setDeployJob(final String deployJob) {
    this.deployJob = deployJob;
  }

  public String getDeployedHash() {
    return this.deployedHash;
  }

  public void setDeployedHash(final String deployedHash) {
    this.deployedHash = deployedHash;
  }

  public String getDeployedImage() {
    return this.deployedImage;
  }

  public void setDeployedImage(final String deployedImage) {
    this.deployedImage = deployedImage;
  }

  public String getDeployedAt() {
    return this.deployedAt;
  }

  public void setDeployedAt(final String deployedAt) {
    this.deployedAt = deployedAt;
  }

  public String getFailedHash() {
    return this.failedHash;
  }

  public void setFailedHash(final String failedHash) {
    this.failedHash = failedHash;
  }

  public List<Condition> getConditions() {
    return this.conditions;
  }

  public void setConditions(final List<Condition> conditions) {
    this.conditions = conditions == null ? new ArrayList<>() : new ArrayList<>(conditions);
  }
}
