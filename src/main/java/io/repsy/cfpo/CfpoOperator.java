package io.repsy.cfpo;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javaoperatorsdk.operator.Operator;
import io.repsy.cfpo.cloudflare.CloudflareClient;
import io.repsy.cfpo.config.OperatorConfig;
import io.repsy.cfpo.reconciler.CloudflarePageReconciler;
import io.repsy.cfpo.reconciler.DeployJobFactory;

public final class CfpoOperator {

  private static final Logger LOG = LoggerFactory.getLogger(CfpoOperator.class);

  private CfpoOperator() {}

  public static void main(String[] args) throws IOException {
    OperatorConfig config;
    try {
      config = OperatorConfig.fromEnvironment();
    } catch (IllegalArgumentException e) {
      LOG.error("Invalid configuration: {}", e.getMessage());
      System.exit(1);
      return;
    }
    LOG.info("Starting cfpo with {}", config);

    Operator operator =
        new Operator(
            o ->
                o.withStopOnInformerErrorDuringStartup(true)
                    .withReconciliationTerminationTimeout(Duration.ofSeconds(20)));
    register(operator, config);
    operator.installShutdownHook();
    HealthServer.start(config.healthPort(), operator);
    operator.start();
  }

  /** Registers the reconciler; shared with the integration tests. */
  public static void register(Operator operator, OperatorConfig config) {
    CloudflareClient cloudflare =
        new CloudflareClient(config.apiBaseUrl(), config.apiToken(), config.accountId());
    CloudflarePageReconciler reconciler =
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
