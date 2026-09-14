package io.repsy.cfpo;

import io.javaoperatorsdk.operator.Operator;
import io.repsy.cfpo.cloudflare.CloudflareClient;
import io.repsy.cfpo.config.OperatorConfig;
import io.repsy.cfpo.reconciler.CloudflarePageReconciler;
import io.repsy.cfpo.reconciler.DeployJobFactory;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CfpoOperator {

  private static final Logger LOG = LoggerFactory.getLogger(CfpoOperator.class);
  private static final long RECONCILIATION_TERMINATION_TIMEOUT_SECONDS = 20;

  private CfpoOperator() {}

  public static void main(final String[] args) throws IOException {
    final OperatorConfig config;
    try {
      config = OperatorConfig.fromEnvironment();
    } catch (final IllegalArgumentException e) {
      LOG.error("Invalid configuration: {}", e.getMessage());
      System.exit(1);
      return;
    }
    LOG.info("Starting cfpo with {}", config);

    final Operator operator =
        new Operator(
            o ->
                o.withStopOnInformerErrorDuringStartup(true)
                    .withReconciliationTerminationTimeout(
                        Duration.ofSeconds(RECONCILIATION_TERMINATION_TIMEOUT_SECONDS)));
    register(operator, config);
    operator.installShutdownHook();
    HealthServer.start(config.healthPort(), operator);
    operator.start();
  }

  /** Registers the reconciler; shared with the integration tests. */
  public static void register(final Operator operator, final OperatorConfig config) {
    final CloudflareClient cloudflare =
        new CloudflareClient(config.apiBaseUrl(), config.apiToken(), config.accountId());
    final CloudflarePageReconciler reconciler =
        new CloudflarePageReconciler(cloudflare, config, new DeployJobFactory(config));
    operator.register(
        reconciler,
        overrider -> {
          if (!config.watchNamespaces().isEmpty()) {
            overrider.settingNamespaces(config.watchNamespaces());
          }
        });
  }
}
