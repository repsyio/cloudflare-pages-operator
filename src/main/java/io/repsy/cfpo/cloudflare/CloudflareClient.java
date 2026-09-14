package io.repsy.cfpo.cloudflare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.repsy.cfpo.cloudflare.model.ApiError;
import io.repsy.cfpo.cloudflare.model.DnsRecord;
import io.repsy.cfpo.cloudflare.model.PagesDomain;
import io.repsy.cfpo.cloudflare.model.PagesProject;
import io.repsy.cfpo.cloudflare.model.Zone;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Minimal client for the parts of the Cloudflare v4 API the operator needs. */
public class CloudflareClient {

  private static final int FIRST_ERROR_HTTP_STATUS = 300;

  public static final String DEFAULT_BASE_URL = "https://api.cloudflare.com/client/v4";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final String baseUrl;
  private final String apiToken;
  private final String accountId;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public CloudflareClient(final String baseUrl, final String apiToken, final String accountId) {
    this(
        baseUrl,
        apiToken,
        accountId,
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  CloudflareClient(
      final String baseUrl, final String apiToken, final String accountId, final HttpClient http) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiToken = apiToken;
    this.accountId = accountId;
    this.http = http;
    this.mapper =
        new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  // ---- Pages projects ----

  public Optional<PagesProject> getProject(final String projectName) {
    return this.getOptional(this.projectPath(projectName), PagesProject.class);
  }

  public PagesProject createProject(final String projectName, final String productionBranch) {
    return this.send(
        "POST",
        this.accountPath("/pages/projects"),
        Map.of("name", projectName, "production_branch", productionBranch),
        PagesProject.class);
  }

  public void deleteProject(final String projectName) {
    this.deleteIgnoringNotFound(this.projectPath(projectName));
  }

  // ---- Pages custom domains ----

  public List<PagesDomain> listDomains(final String projectName) {
    return this.sendList("GET", this.projectPath(projectName) + "/domains", PagesDomain.class);
  }

  public PagesDomain addDomain(final String projectName, final String domain) {
    return this.send(
        "POST",
        this.projectPath(projectName) + "/domains",
        Map.of("name", domain),
        PagesDomain.class);
  }

  public void deleteDomain(final String projectName, final String domain) {
    this.deleteIgnoringNotFound(this.projectPath(projectName) + "/domains/" + segment(domain));
  }

  // ---- Zones & DNS ----

  /**
   * Finds the zone a hostname belongs to by trying each parent name, e.g. {@code a.b.example.com}
   * then {@code b.example.com} then {@code example.com}.
   */
  public Optional<Zone> findZoneForDomain(final String domain) {
    String candidate = domain.toLowerCase(Locale.ROOT);
    while (candidate.contains(".")) {
      final List<Zone> zones =
          this.sendList(
              "GET",
              "/zones?name=" + query(candidate) + "&account.id=" + query(this.accountId),
              Zone.class);
      if (!zones.isEmpty()) {
        return Optional.of(zones.getFirst());
      }
      candidate = candidate.substring(candidate.indexOf('.') + 1);
    }
    return Optional.empty();
  }

  /** Lists records of any type with exactly this name. */
  public List<DnsRecord> listRecordsByName(final String zoneId, final String name) {
    return this.sendList(
        "GET", "/zones/" + segment(zoneId) + "/dns_records?name=" + query(name), DnsRecord.class);
  }

  public DnsRecord createCnameRecord(
      final String zoneId, final String name, final String target, final String comment) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "CNAME");
    body.put("name", name);
    body.put("content", target);
    body.put("proxied", true);
    body.put("ttl", 1);
    body.put("comment", comment);
    return this.send("POST", "/zones/" + segment(zoneId) + "/dns_records", body, DnsRecord.class);
  }

  public DnsRecord updateCnameRecord(
      final String zoneId, final String recordId, final String target, final String comment) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("content", target);
    body.put("proxied", true);
    body.put("comment", comment);
    return this.send(
        "PATCH",
        "/zones/" + segment(zoneId) + "/dns_records/" + segment(recordId),
        body,
        DnsRecord.class);
  }

  public void deleteDnsRecord(final String zoneId, final String recordId) {
    this.deleteIgnoringNotFound("/zones/" + segment(zoneId) + "/dns_records/" + segment(recordId));
  }

  // ---- plumbing ----

  private String accountPath(final String suffix) {
    return "/accounts/" + segment(this.accountId) + suffix;
  }

  private String projectPath(final String projectName) {
    return this.accountPath("/pages/projects/" + segment(projectName));
  }

  private <T> Optional<T> getOptional(final String path, final Class<T> type) {
    try {
      return Optional.of(this.send("GET", path, null, type));
    } catch (final CloudflareApiException e) {
      if (e.isNotFound()) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private void deleteIgnoringNotFound(final String path) {
    try {
      this.execute("DELETE", path, null);
    } catch (final CloudflareApiException e) {
      if (!e.isNotFound()) {
        throw e;
      }
    }
  }

  private <T> T send(
      final String method, final String path, final Object body, final Class<T> type) {
    final JsonNode result = this.execute(method, path, body);
    return this.mapper.convertValue(result, type);
  }

  private <T> List<T> sendList(final String method, final String path, final Class<T> type) {
    final JsonNode result = this.execute(method, path, null);
    if (result == null || !result.isArray()) {
      return List.of();
    }
    return this.mapper.convertValue(
        result, this.mapper.getTypeFactory().constructCollectionType(List.class, type));
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  private JsonNode execute(final String method, final String path, final Object body) {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(this.baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + this.apiToken)
            .header("Accept", "application/json");
    if (body != null) {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(this.toJson(body)));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }

    final HttpResponse<String> response;
    try {
      response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (final IOException e) {
      throw new CloudflareApiException(method, path, 0, List.of(), null, e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CloudflareApiException(method, path, 0, List.of(), null, e);
    }

    final JsonNode envelope = this.parse(response.body());
    final boolean success = envelope != null && envelope.path("success").asBoolean(false);
    if (response.statusCode() >= FIRST_ERROR_HTTP_STATUS || !success) {
      throw new CloudflareApiException(
          method, path, response.statusCode(), this.errors(envelope), retryAfter(response), null);
    }
    return envelope.get("result");
  }

  private List<ApiError> errors(final JsonNode envelope) {
    if (envelope == null || !envelope.path("errors").isArray()) {
      return List.of();
    }
    return this.mapper.convertValue(
        envelope.get("errors"),
        this.mapper.getTypeFactory().constructCollectionType(List.class, ApiError.class));
  }

  private static Duration retryAfter(final HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .flatMap(
            value -> {
              try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
              } catch (final NumberFormatException e) {
                return Optional.empty();
              }
            })
        .orElse(null);
  }

  private JsonNode parse(final String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return this.mapper.readTree(body);
    } catch (final JsonProcessingException e) {
      return null;
    }
  }

  private String toJson(final Object body) {
    try {
      return this.mapper.writeValueAsString(body);
    } catch (final JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize request body", e);
    }
  }

  private static String segment(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String query(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
