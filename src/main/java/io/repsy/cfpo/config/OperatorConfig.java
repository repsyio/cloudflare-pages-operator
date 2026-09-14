package io.repsy.cfpo.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.repsy.cfpo.cloudflare.CloudflareClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operator settings, read from environment variables set by the Helm chart. */
public record OperatorConfig(
    String apiToken,
    String accountId,
    String apiBaseUrl,
    String deployerImage,
    String operatorNamespace,
    String credentialsSecretName,
    String credentialsSecretKey,
    List<String> deployPullSecrets,
    Set<String> watchNamespaces,
    Duration resyncInterval,
    long deployJobTtlSeconds,
    long deployJobDeadlineSeconds,
    ResourceRequirements deployJobResources,
    int healthPort) {

  private static final long DEFAULT_RESYNC_SECONDS = 600;
  private static final long DEFAULT_DEPLOY_JOB_TTL_SECONDS = 3600;
  private static final long DEFAULT_DEPLOY_JOB_DEADLINE_SECONDS = 900;
  private static final int DEFAULT_HEALTH_PORT = 8080;

  public static OperatorConfig fromEnvironment() {
    return from(System.getenv());
  }

  public static OperatorConfig from(final Map<String, String> env) {
    final List<String> missing = new ArrayList<>();
    final String apiToken = required(env, "CLOUDFLARE_API_TOKEN", missing);
    final String accountId = required(env, "CLOUDFLARE_ACCOUNT_ID", missing);
    final String deployerImage = required(env, "DEPLOYER_IMAGE", missing);
    final String operatorNamespace = required(env, "OPERATOR_NAMESPACE", missing);
    final String secretName = required(env, "CREDENTIALS_SECRET_NAME", missing);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "Missing required environment variables: " + String.join(", ", missing));
    }

    return new OperatorConfig(
        apiToken,
        accountId,
        optional(env, "CLOUDFLARE_API_BASE_URL", CloudflareClient.DEFAULT_BASE_URL),
        deployerImage,
        operatorNamespace,
        secretName,
        optional(env, "CREDENTIALS_SECRET_KEY", "api-token"),
        List.copyOf(csv(env.get("DEPLOY_PULL_SECRETS"))),
        Set.copyOf(csv(env.get("WATCH_NAMESPACES"))),
        Duration.ofSeconds(positiveLong(env, "RESYNC_SECONDS", DEFAULT_RESYNC_SECONDS)),
        positiveLong(env, "DEPLOY_JOB_TTL_SECONDS", DEFAULT_DEPLOY_JOB_TTL_SECONDS),
        positiveLong(env, "DEPLOY_JOB_DEADLINE_SECONDS", DEFAULT_DEPLOY_JOB_DEADLINE_SECONDS),
        resources(env.get("DEPLOY_JOB_RESOURCES")),
        (int) positiveLong(env, "HEALTH_PORT", DEFAULT_HEALTH_PORT));
  }

  private static String required(
      final Map<String, String> env, final String key, final List<String> missing) {
    final String value = env.get(key);
    if (value == null || value.isBlank()) {
      missing.add(key);
      return null;
    }
    return value.trim();
  }

  private static String optional(
      final Map<String, String> env, final String key, final String fallback) {
    final String value = env.get(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static long positiveLong(
      final Map<String, String> env, final String key, final long fallback) {
    final String value = env.get(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      final long parsed = Long.parseLong(value.trim());
      if (parsed <= 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be a positive integer, got: " + value);
    }
  }

  private static ResourceRequirements resources(final String json) {
    if (json == null || json.isBlank()) {
      return new ResourceRequirementsBuilder()
          .addToRequests("cpu", new Quantity("100m"))
          .addToRequests("memory", new Quantity("256Mi"))
          .addToLimits("memory", new Quantity("1Gi"))
          .build();
    }
    try {
      return new ObjectMapper().readValue(json, ResourceRequirements.class);
    } catch (final JsonProcessingException e) {
      throw new IllegalArgumentException("DEPLOY_JOB_RESOURCES is not valid JSON: " + json, e);
    }
  }

  private static Set<String> csv(final String value) {
    final Set<String> result = new LinkedHashSet<>();
    if (value != null) {
      Arrays.stream(value.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .forEach(result::add);
    }
    return result;
  }

  @Override
  public String toString() {
    return ("OperatorConfig[accountId=%s, apiBaseUrl=%s, deployerImage=%s, "
            + "operatorNamespace=%s, credentialsSecret=%s/%s, deployPullSecrets=%s, "
            + "watchNamespaces=%s, resyncInterval=%s]")
        .formatted(
            this.accountId,
            this.apiBaseUrl,
            this.deployerImage,
            this.operatorNamespace,
            this.credentialsSecretName,
            this.credentialsSecretKey,
            this.deployPullSecrets,
            this.watchNamespaces.isEmpty() ? "<all>" : this.watchNamespaces,
            this.resyncInterval);
  }
}
