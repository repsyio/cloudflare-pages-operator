package io.repsy.cfpo.crd;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.fabric8.crd.generator.annotation.PrinterColumn;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;

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
      String type, boolean value, String reason, String message, Long generation) {
    String statusValue = value ? "True" : "False";
    Optional<Condition> existing = condition(type);
    if (existing.isEmpty()) {
      conditions.add(
          new ConditionBuilder()
              .withType(type)
              .withStatus(statusValue)
              .withReason(reason)
              .withMessage(message)
              .withObservedGeneration(generation)
              .withLastTransitionTime(now())
              .build());
      return;
    }
    Condition condition = existing.get();
    if (!statusValue.equals(condition.getStatus())) {
      condition.setLastTransitionTime(now());
    }
    condition.setStatus(statusValue);
    condition.setReason(reason);
    condition.setMessage(message);
    condition.setObservedGeneration(generation);
  }

  public Optional<Condition> condition(String type) {
    return conditions.stream().filter(c -> type.equals(c.getType())).findFirst();
  }

  @JsonIgnore
  public boolean isConditionTrue(String type) {
    return condition(type).map(c -> "True".equals(c.getStatus())).orElse(false);
  }

  private static String now() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  public String getPhase() {
    return phase;
  }

  public void setPhase(String phase) {
    this.phase = phase;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Long getObservedGeneration() {
    return observedGeneration;
  }

  public void setObservedGeneration(Long observedGeneration) {
    this.observedGeneration = observedGeneration;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getPagesDevUrl() {
    return pagesDevUrl;
  }

  public void setPagesDevUrl(String pagesDevUrl) {
    this.pagesDevUrl = pagesDevUrl;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public Boolean getProjectCreated() {
    return projectCreated;
  }

  public void setProjectCreated(Boolean projectCreated) {
    this.projectCreated = projectCreated;
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public String getDomainStatus() {
    return domainStatus;
  }

  public void setDomainStatus(String domainStatus) {
    this.domainStatus = domainStatus;
  }

  public String getZoneId() {
    return zoneId;
  }

  public void setZoneId(String zoneId) {
    this.zoneId = zoneId;
  }

  public String getDnsRecordId() {
    return dnsRecordId;
  }

  public void setDnsRecordId(String dnsRecordId) {
    this.dnsRecordId = dnsRecordId;
  }

  public String getDeployJob() {
    return deployJob;
  }

  public void setDeployJob(String deployJob) {
    this.deployJob = deployJob;
  }

  public String getDeployedHash() {
    return deployedHash;
  }

  public void setDeployedHash(String deployedHash) {
    this.deployedHash = deployedHash;
  }

  public String getDeployedImage() {
    return deployedImage;
  }

  public void setDeployedImage(String deployedImage) {
    this.deployedImage = deployedImage;
  }

  public String getDeployedAt() {
    return deployedAt;
  }

  public void setDeployedAt(String deployedAt) {
    this.deployedAt = deployedAt;
  }

  public String getFailedHash() {
    return failedHash;
  }

  public void setFailedHash(String failedHash) {
    this.failedHash = failedHash;
  }

  public List<Condition> getConditions() {
    return conditions;
  }

  public void setConditions(List<Condition> conditions) {
    this.conditions = conditions == null ? new ArrayList<>() : new ArrayList<>(conditions);
  }
}
