package io.repsy.cfpo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.Quantity;
import io.repsy.cfpo.cloudflare.CloudflareClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class OperatorConfigTest {

  /** Minimal valid environment, shared with other tests. */
  public static Map<String, String> requiredEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("CLOUDFLARE_API_TOKEN", "secret-token");
    env.put("CLOUDFLARE_ACCOUNT_ID", "acc");
    env.put("DEPLOYER_IMAGE", "cfpo-deployer:1");
    env.put("OPERATOR_NAMESPACE", "cfpo-system");
    env.put("CREDENTIALS_SECRET_NAME", "cfpo");
    return env;
  }

  @Test
  void appliesDefaults() {
    OperatorConfig config = OperatorConfig.from(requiredEnv());

    assertThat(config.apiBaseUrl()).isEqualTo(CloudflareClient.DEFAULT_BASE_URL);
    assertThat(config.credentialsSecretKey()).isEqualTo("api-token");
    assertThat(config.deployPullSecrets()).isEmpty();
    assertThat(config.watchNamespaces()).isEmpty();
    assertThat(config.resyncInterval()).isEqualTo(Duration.ofMinutes(10));
    assertThat(config.deployJobResources().getLimits())
        .containsEntry("memory", new Quantity("1Gi"));
  }

  @Test
  void parsesOptionalValues() {
    Map<String, String> env = requiredEnv();
    env.put("DEPLOY_PULL_SECRETS", "regcred, other ,");
    env.put("WATCH_NAMESPACES", "apps,web");
    env.put("RESYNC_SECONDS", "60");
    env.put("DEPLOY_JOB_RESOURCES", "{\"limits\":{\"memory\":\"2Gi\"}}");

    OperatorConfig config = OperatorConfig.from(env);

    assertThat(config.deployPullSecrets()).containsExactly("regcred", "other");
    assertThat(config.watchNamespaces()).containsExactlyInAnyOrder("apps", "web");
    assertThat(config.resyncInterval()).isEqualTo(Duration.ofSeconds(60));
    assertThat(config.deployJobResources().getLimits())
        .containsEntry("memory", new Quantity("2Gi"));
  }

  @Test
  void reportsAllMissingVariables() {
    Map<String, String> env = requiredEnv();
    env.remove("CLOUDFLARE_API_TOKEN");
    env.put("DEPLOYER_IMAGE", " ");

    assertThatThrownBy(() -> OperatorConfig.from(env))
        .hasMessageContaining("CLOUDFLARE_API_TOKEN")
        .hasMessageContaining("DEPLOYER_IMAGE");
  }

  @Test
  void rejectsInvalidNumbers() {
    Map<String, String> env = requiredEnv();
    env.put("RESYNC_SECONDS", "-1");

    assertThatThrownBy(() -> OperatorConfig.from(env)).hasMessageContaining("RESYNC_SECONDS");
  }

  @Test
  void toStringDoesNotLeakToken() {
    assertThat(OperatorConfig.from(requiredEnv()).toString()).doesNotContain("secret-token");
  }
}
