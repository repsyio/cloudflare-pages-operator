package io.repsy.cfpo.reconciler;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.fabric8.kubeapitest.junit.EnableKubeAPIServer;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.repsy.cfpo.CfpoOperator;
import io.repsy.cfpo.config.OperatorConfig;
import io.repsy.cfpo.config.OperatorConfigTest;
import io.repsy.cfpo.crd.CloudflarePage;
import io.repsy.cfpo.crd.CloudflarePageSpec;
import io.repsy.cfpo.crd.CloudflarePageStatus;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Runs the operator against a throwaway kube-apiserver (no controllers, so Job status is set by the
 * test) and a WireMock stand-in for the Cloudflare API.
 */
@EnableKubeAPIServer
class CloudflarePageReconcilerIT {

  static final String OPERATOR_NS = "cfpo-system";
  static final String APP_NS = "apps";
  static final String CRD_FILE = "charts/cfpo/crds/cloudflarepages.pages.repsy.io-v1.yml";
  static final String IMAGE = "registry.example.com/web:1.0.0";
  static final String IMAGE_V2 = "registry.example.com/web:2.0.0";
  static final String DIRECTORY = "/dist";
  static final Duration TIMEOUT = Duration.ofSeconds(60);

  static KubernetesClient client;

  @RegisterExtension
  static WireMockExtension cloudflare =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private Operator operator;

  @BeforeAll
  static void installCrdAndNamespaces() throws Exception {
    try (InputStream crd = Files.newInputStream(Path.of(CRD_FILE))) {
      client.load(crd).serverSideApply();
    }
    for (String ns : List.of(OPERATOR_NS, APP_NS)) {
      client
          .resource(new NamespaceBuilder().withNewMetadata().withName(ns).endMetadata().build())
          .serverSideApply();
    }
    await()
        .atMost(TIMEOUT)
        .ignoreExceptions()
        .until(() -> client.resources(CloudflarePage.class).inNamespace(APP_NS).list() != null);
  }

  @BeforeEach
  void startOperator() {
    Map<String, String> env = OperatorConfigTest.requiredEnv();
    env.put("CLOUDFLARE_API_BASE_URL", cloudflare.baseUrl() + "/client/v4");
    env.put("OPERATOR_NAMESPACE", OPERATOR_NS);
    OperatorConfig config = OperatorConfig.from(env);

    operator = new Operator(o -> o.withKubernetesClient(client).withCloseClientOnStop(false));
    CfpoOperator.register(operator, config);
    operator.start();
  }

  @AfterEach
  void stopOperator() {
    operator.stop();
    for (CloudflarePage page :
        client.resources(CloudflarePage.class).inAnyNamespace().list().getItems()) {
      client
          .resource(page)
          .edit(
              p -> {
                p.getMetadata().setFinalizers(List.of());
                return p;
              });
      client.resource(page).delete();
    }
    client.batch().v1().jobs().inNamespace(OPERATOR_NS).delete();
  }

  @Test
  void deploysAttachesDomainConfiguresDnsAndCleansUp() {
    stubCloudflare("apps-web", "web.example.com");
    CloudflarePage page = client.resource(page("web", "web.example.com")).create();

    Job job = awaitJob("web");
    assertThat(job.getSpec().getTemplate().getSpec().getContainers().getFirst().getArgs())
        .contains("--project-name=apps-web");
    assertThat(job.getMetadata().getLabels())
        .containsEntry(DeployJobFactory.OWNER_UID_LABEL, page.getMetadata().getUid());
    awaitPhase("web", CloudflarePageStatus.PHASE_DEPLOYING);

    complete(job);

    CloudflarePageStatus status = awaitPhase("web", CloudflarePageStatus.PHASE_READY);
    assertThat(status.getUrl()).isEqualTo("https://web.example.com");
    assertThat(status.getPagesDevUrl()).isEqualTo("https://apps-web.pages.dev");
    assertThat(status.getProjectCreated()).isTrue();
    assertThat(status.getDeployedImage()).isEqualTo(IMAGE);
    assertThat(status.getDnsRecordId()).isEqualTo("rec-1");
    assertThat(status.getConditions())
        .allSatisfy(c -> assertThat(c.getStatus()).as(c.getType()).isEqualTo("True"));

    cloudflare.verify(
        postRequestedFor(urlEqualTo("/client/v4/accounts/acc/pages/projects"))
            .withRequestBody(
                equalToJson("{\"name\":\"apps-web\",\"production_branch\":\"main\"}")));
    cloudflare.verify(
        postRequestedFor(urlEqualTo("/client/v4/zones/zone-1/dns_records"))
            .withRequestBody(
                equalToJson(
                    "{\"type\":\"CNAME\",\"name\":\"web.example.com\",\"content\":\"apps-web.pages.dev\",\"proxied\":true,\"ttl\":1,\"comment\":\"managed-by: cfpo apps/web\"}")));

    client.resource(page).delete();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(
                        client
                            .resources(CloudflarePage.class)
                            .inNamespace(APP_NS)
                            .withName("web")
                            .get())
                    .isNull());
    cloudflare.verify(deleteRequestedFor(urlEqualTo("/client/v4/zones/zone-1/dns_records/rec-1")));
    cloudflare.verify(
        deleteRequestedFor(
            urlEqualTo("/client/v4/accounts/acc/pages/projects/apps-web/domains/web.example.com")));
    cloudflare.verify(
        deleteRequestedFor(urlEqualTo("/client/v4/accounts/acc/pages/projects/apps-web")));
    assertThat(
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(OPERATOR_NS)
                .withLabel(DeployJobFactory.OWNER_UID_LABEL, page.getMetadata().getUid())
                .list()
                .getItems())
        .isEmpty();
  }

  @Test
  void failedDeployIsReportedAndNotRetried() {
    stubCloudflare("apps-broken", "broken.example.com");
    CloudflarePage page = client.resource(page("broken", "broken.example.com")).create();

    fail(awaitJob("broken"));

    CloudflarePageStatus status = awaitPhase("broken", CloudflarePageStatus.PHASE_FAILED);
    assertThat(status.getMessage()).contains("BackoffLimitExceeded");
    assertThat(status.condition(CloudflarePageStatus.CONDITION_DEPLOYED))
        .hasValueSatisfying(c -> assertThat(c.getReason()).isEqualTo("DeployFailed"));
    assertThat(
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(OPERATOR_NS)
                .withLabel(DeployJobFactory.OWNER_UID_LABEL, page.getMetadata().getUid())
                .list()
                .getItems())
        .hasSize(1);
  }

  @Test
  void newSpecStopsSupersededDeployJob() {
    stubCloudflare("apps-moved", "moved.example.com");
    client.resource(page("moved", "moved.example.com")).create();
    Job first = awaitJob("moved");

    await()
        .atMost(TIMEOUT)
        .ignoreExceptions()
        .untilAsserted(
            () ->
                client
                    .resources(CloudflarePage.class)
                    .inNamespace(APP_NS)
                    .withName("moved")
                    .edit(
                        p -> {
                          p.getSpec().setImage(IMAGE_V2);
                          return p;
                        }));

    String secondName =
        Naming.deployJobName(APP_NS, "moved", Naming.deployHash(IMAGE_V2, DIRECTORY));
    await()
        .atMost(TIMEOUT)
        .until(
            () -> client.batch().v1().jobs().inNamespace(OPERATOR_NS).withName(secondName).get(),
            Objects::nonNull);
    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(
                        client
                            .batch()
                            .v1()
                            .jobs()
                            .inNamespace(OPERATOR_NS)
                            .withName(first.getMetadata().getName())
                            .get())
                    .as("superseded job %s", first.getMetadata().getName())
                    .isNull());
  }

  // ---- helpers ----

  static CloudflarePage page(String name, String domain) {
    CloudflarePageSpec spec = new CloudflarePageSpec();
    spec.setImage(IMAGE);
    spec.setDirectory(DIRECTORY);
    spec.setDomain(domain);
    CloudflarePage page = new CloudflarePage();
    page.setMetadata(new ObjectMetaBuilder().withNamespace(APP_NS).withName(name).build());
    page.setSpec(spec);
    return page;
  }

  static Job awaitJob(String pageName) {
    String jobName = Naming.deployJobName(APP_NS, pageName, Naming.deployHash(IMAGE, DIRECTORY));
    return await()
        .atMost(TIMEOUT)
        .until(
            () -> client.batch().v1().jobs().inNamespace(OPERATOR_NS).withName(jobName).get(),
            Objects::nonNull);
  }

  static CloudflarePageStatus awaitPhase(String pageName, String phase) {
    return await()
        .atMost(TIMEOUT)
        .until(
            () -> {
              CloudflarePage current =
                  client
                      .resources(CloudflarePage.class)
                      .inNamespace(APP_NS)
                      .withName(pageName)
                      .get();
              return current == null ? null : current.getStatus();
            },
            s -> s != null && phase.equals(s.getPhase()));
  }

  static void complete(Job job) {
    String now = now();
    job.setStatus(
        new JobStatusBuilder()
            .withStartTime(now)
            .withCompletionTime(now)
            .withSucceeded(1)
            .addNewCondition()
            .withType("SuccessCriteriaMet")
            .withStatus("True")
            .withLastProbeTime(now)
            .withLastTransitionTime(now)
            .endCondition()
            .addNewCondition()
            .withType("Complete")
            .withStatus("True")
            .withLastProbeTime(now)
            .withLastTransitionTime(now)
            .endCondition()
            .build());
    client.resource(job).updateStatus();
  }

  static void fail(Job job) {
    String now = now();
    job.setStatus(
        new JobStatusBuilder()
            .withStartTime(now)
            .withFailed(3)
            .addNewCondition()
            .withType("FailureTarget")
            .withStatus("True")
            .withReason("BackoffLimitExceeded")
            .withMessage("Job has reached the specified backoff limit")
            .withLastProbeTime(now)
            .withLastTransitionTime(now)
            .endCondition()
            .addNewCondition()
            .withType("Failed")
            .withStatus("True")
            .withReason("BackoffLimitExceeded")
            .withMessage("Job has reached the specified backoff limit")
            .withLastProbeTime(now)
            .withLastTransitionTime(now)
            .endCondition()
            .build());
    client.resource(job).updateStatus();
  }

  static String now() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  /** Stateful Cloudflare API: project, domain and DNS record appear once created. */
  static void stubCloudflare(String project, String domain) {
    String projectPath = "/client/v4/accounts/acc/pages/projects/" + project;
    String projectJson =
        "{\"id\":\"p-1\",\"name\":\"%s\",\"subdomain\":\"%s.pages.dev\",\"production_branch\":\"main\"}"
            .formatted(project, project);
    String domainJson = "{\"id\":\"d-1\",\"name\":\"%s\",\"status\":\"active\"}".formatted(domain);
    String recordJson =
        "{\"id\":\"rec-1\",\"type\":\"CNAME\",\"name\":\"%s\",\"content\":\"%s.pages.dev\",\"proxied\":true,\"comment\":\"%s\"}"
            .formatted(
                domain,
                project,
                Naming.dnsComment(APP_NS, domain.substring(0, domain.indexOf('.'))));

    cloudflare.stubFor(
        get(projectPath)
            .inScenario("project")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(404).withBody(error(8000007, "Project not found"))));
    cloudflare.stubFor(
        post("/client/v4/accounts/acc/pages/projects")
            .inScenario("project")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("created")
            .willReturn(okJson(ok(projectJson))));
    cloudflare.stubFor(
        get(projectPath)
            .inScenario("project")
            .whenScenarioStateIs("created")
            .willReturn(okJson(ok(projectJson))));
    cloudflare.stubFor(delete(projectPath).willReturn(okJson(ok("null"))));

    cloudflare.stubFor(
        get(projectPath + "/domains")
            .inScenario("domain")
            .whenScenarioStateIs(STARTED)
            .willReturn(okJson(ok("[]"))));
    cloudflare.stubFor(
        post(projectPath + "/domains")
            .inScenario("domain")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("added")
            .willReturn(okJson(ok(domainJson))));
    cloudflare.stubFor(
        get(projectPath + "/domains")
            .inScenario("domain")
            .whenScenarioStateIs("added")
            .willReturn(okJson(ok("[" + domainJson + "]"))));
    cloudflare.stubFor(delete(projectPath + "/domains/" + domain).willReturn(okJson(ok("null"))));

    cloudflare.stubFor(
        get(urlPathEqualTo("/client/v4/zones"))
            .withQueryParam("name", equalTo(domain))
            .willReturn(okJson(ok("[]"))));
    cloudflare.stubFor(
        get(urlPathEqualTo("/client/v4/zones"))
            .withQueryParam("name", equalTo("example.com"))
            .willReturn(
                okJson(
                    ok("[{\"id\":\"zone-1\",\"name\":\"example.com\",\"status\":\"active\"}]"))));

    cloudflare.stubFor(
        get(urlPathEqualTo("/client/v4/zones/zone-1/dns_records"))
            .inScenario("dns")
            .whenScenarioStateIs(STARTED)
            .willReturn(okJson(ok("[]"))));
    cloudflare.stubFor(
        post("/client/v4/zones/zone-1/dns_records")
            .inScenario("dns")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("created")
            .willReturn(okJson(ok(recordJson))));
    cloudflare.stubFor(
        get(urlPathEqualTo("/client/v4/zones/zone-1/dns_records"))
            .inScenario("dns")
            .whenScenarioStateIs("created")
            .willReturn(okJson(ok("[" + recordJson + "]"))));
    cloudflare.stubFor(
        delete("/client/v4/zones/zone-1/dns_records/rec-1")
            .willReturn(okJson(ok("{\"id\":\"rec-1\"}"))));
  }

  static String ok(String result) {
    return "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":" + result + "}";
  }

  static String error(int code, String message) {
    return "{\"success\":false,\"errors\":[{\"code\":"
        + code
        + ",\"message\":\""
        + message
        + "\"}],\"result\":null}";
  }
}
