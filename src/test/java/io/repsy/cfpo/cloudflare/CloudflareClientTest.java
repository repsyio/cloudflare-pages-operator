package io.repsy.cfpo.cloudflare;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.repsy.cfpo.cloudflare.model.PagesProject;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CloudflareClientTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private CloudflareClient client;

  @BeforeEach
  void setUp() {
    client = new CloudflareClient(wm.baseUrl() + "/client/v4/", "token-1", "acc");
  }

  static String ok(String result) {
    return "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":" + result + "}";
  }

  static String error(int code, String message) {
    return "{\"success\":false,\"errors\":[{\"code\":"
        + code
        + ",\"message\":\""
        + message
        + "\"}],\"messages\":[],\"result\":null}";
  }

  @Test
  void getProjectReturnsEmptyOn404() {
    wm.stubFor(
        get("/client/v4/accounts/acc/pages/projects/web")
            .willReturn(aResponse().withStatus(404).withBody(error(8000007, "Project not found"))));

    assertThat(client.getProject("web")).isEmpty();
  }

  @Test
  void createProjectSendsAuthAndBody() {
    wm.stubFor(
        post("/client/v4/accounts/acc/pages/projects")
            .willReturn(
                okJson(
                    ok(
                        "{\"id\":\"p1\",\"name\":\"web\",\"subdomain\":\"web-3x1.pages.dev\",\"production_branch\":\"main\",\"extra\":1}"))));

    PagesProject project = client.createProject("web", "main");

    assertThat(project.subdomain()).isEqualTo("web-3x1.pages.dev");
    assertThat(project.productionBranch()).isEqualTo("main");
    wm.verify(
        postRequestedFor(urlEqualTo("/client/v4/accounts/acc/pages/projects"))
            .withHeader("Authorization", equalTo("Bearer token-1"))
            .withRequestBody(equalToJson("{\"name\":\"web\",\"production_branch\":\"main\"}")));
  }

  @Test
  void errorsCarryStatusCodesAndRetryHint() {
    wm.stubFor(
        post("/client/v4/accounts/acc/pages/projects/web/domains")
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Retry-After", "7")
                    .withBody(error(10000, "rate limited"))));

    assertThatThrownBy(() -> client.addDomain("web", "app.example.com"))
        .isInstanceOfSatisfying(
            CloudflareApiException.class,
            e -> {
              assertThat(e.isRateLimited()).isTrue();
              assertThat(e.isTransient()).isTrue();
              assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7));
              assertThat(e.errors()).extracting("code").containsExactly(10000);
              assertThat(e.getMessage()).contains("HTTP 429").doesNotContain("token-1");
            });
  }

  @Test
  void unsuccessfulEnvelopeWith200IsAnError() {
    wm.stubFor(
        get("/client/v4/accounts/acc/pages/projects/web/domains")
            .willReturn(okJson(error(8000000, "bad request"))));

    assertThatThrownBy(() -> client.listDomains("web"))
        .isInstanceOfSatisfying(
            CloudflareApiException.class, e -> assertThat(e.isTransient()).isFalse());
  }

  @Test
  void findsZoneByWalkingUpTheDomain() {
    wm.stubFor(
        get(urlPathEqualTo("/client/v4/zones"))
            .withQueryParam("name", equalTo("a.b.example.com"))
            .willReturn(okJson(ok("[]"))));
    wm.stubFor(
        get(urlPathEqualTo("/client/v4/zones"))
            .withQueryParam("name", equalTo("b.example.com"))
            .willReturn(okJson(ok("[]"))));
    wm.stubFor(
        get(urlPathEqualTo("/client/v4/zones"))
            .withQueryParam("name", equalTo("example.com"))
            .withQueryParam("account.id", equalTo("acc"))
            .willReturn(
                okJson(ok("[{\"id\":\"z1\",\"name\":\"example.com\",\"status\":\"active\"}]"))));

    assertThat(client.findZoneForDomain("A.b.example.com"))
        .hasValueSatisfying(z -> assertThat(z.id()).isEqualTo("z1"));
    wm.verify(
        0,
        getRequestedFor(urlPathEqualTo("/client/v4/zones")).withQueryParam("name", equalTo("com")));
  }

  @Test
  void zoneLookupReturnsEmptyWhenNoParentMatches() {
    wm.stubFor(get(urlPathEqualTo("/client/v4/zones")).willReturn(okJson(ok("[]"))));

    assertThat(client.findZoneForDomain("app.example.org")).isEmpty();
  }

  @Test
  void deletesIgnoreNotFound() {
    wm.stubFor(
        delete("/client/v4/accounts/acc/pages/projects/web/domains/app.example.com")
            .willReturn(aResponse().withStatus(404).withBody(error(8000013, "not found"))));
    wm.stubFor(
        delete("/client/v4/zones/z1/dns_records/r1").willReturn(okJson(ok("{\"id\":\"r1\"}"))));

    client.deleteDomain("web", "app.example.com");
    client.deleteDnsRecord("z1", "r1");

    wm.verify(deleteRequestedFor(urlEqualTo("/client/v4/zones/z1/dns_records/r1")));
  }

  @Test
  void networkFailuresAreTransient() {
    CloudflareClient unreachable = new CloudflareClient("http://127.0.0.1:1", "t", "acc");

    assertThatThrownBy(() -> unreachable.getProject("web"))
        .isInstanceOfSatisfying(
            CloudflareApiException.class,
            e -> {
              assertThat(e.status()).isZero();
              assertThat(e.isTransient()).isTrue();
            });
  }
}
