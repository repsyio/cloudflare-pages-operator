package io.repsy.cfpo.cloudflare;

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

/** Minimal client for the parts of the Cloudflare v4 API the operator needs. */
public class CloudflareClient {

  public static final String DEFAULT_BASE_URL = "https://api.cloudflare.com/client/v4";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final String baseUrl;
  private final String apiToken;
  private final String accountId;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public CloudflareClient(String baseUrl, String apiToken, String accountId) {
    this(
        baseUrl,
        apiToken,
        accountId,
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  CloudflareClient(String baseUrl, String apiToken, String accountId, HttpClient http) {
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

  public Optional<PagesProject> getProject(String projectName) {
    return getOptional(projectPath(projectName), PagesProject.class);
  }

  public PagesProject createProject(String projectName, String productionBranch) {
    return send(
        "POST",
        accountPath("/pages/projects"),
        Map.of("name", projectName, "production_branch", productionBranch),
        PagesProject.class);
  }

  public void deleteProject(String projectName) {
    deleteIgnoringNotFound(projectPath(projectName));
  }

  // ---- Pages custom domains ----

  public List<PagesDomain> listDomains(String projectName) {
    return sendList("GET", projectPath(projectName) + "/domains", PagesDomain.class);
  }

  public PagesDomain addDomain(String projectName, String domain) {
    return send(
        "POST", projectPath(projectName) + "/domains", Map.of("name", domain), PagesDomain.class);
  }

  public void deleteDomain(String projectName, String domain) {
    deleteIgnoringNotFound(projectPath(projectName) + "/domains/" + segment(domain));
  }

  // ---- Zones & DNS ----

  /**
   * Finds the zone a hostname belongs to by trying each parent name, e.g. {@code a.b.example.com}
   * then {@code b.example.com} then {@code example.com}.
   */
  public Optional<Zone> findZoneForDomain(String domain) {
    String candidate = domain.toLowerCase(Locale.ROOT);
    while (candidate.contains(".")) {
      List<Zone> zones =
          sendList(
              "GET",
              "/zones?name=" + query(candidate) + "&account.id=" + query(accountId),
              Zone.class);
      if (!zones.isEmpty()) {
        return Optional.of(zones.getFirst());
      }
      candidate = candidate.substring(candidate.indexOf('.') + 1);
    }
    return Optional.empty();
  }

  /** Lists records of any type with exactly this name. */
  public List<DnsRecord> listRecordsByName(String zoneId, String name) {
    return sendList(
        "GET", "/zones/" + segment(zoneId) + "/dns_records?name=" + query(name), DnsRecord.class);
  }

  public DnsRecord createCnameRecord(String zoneId, String name, String target, String comment) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "CNAME");
    body.put("name", name);
    body.put("content", target);
    body.put("proxied", true);
    body.put("ttl", 1);
    body.put("comment", comment);
    return send("POST", "/zones/" + segment(zoneId) + "/dns_records", body, DnsRecord.class);
  }

  public DnsRecord updateCnameRecord(String zoneId, String recordId, String target, String comment) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("content", target);
    body.put("proxied", true);
    body.put("comment", comment);
    return send(
        "PATCH",
        "/zones/" + segment(zoneId) + "/dns_records/" + segment(recordId),
        body,
        DnsRecord.class);
  }

  public void deleteDnsRecord(String zoneId, String recordId) {
    deleteIgnoringNotFound("/zones/" + segment(zoneId) + "/dns_records/" + segment(recordId));
  }

  // ---- plumbing ----

  private String accountPath(String suffix) {
    return "/accounts/" + segment(accountId) + suffix;
  }

  private String projectPath(String projectName) {
    return accountPath("/pages/projects/" + segment(projectName));
  }

  private <T> Optional<T> getOptional(String path, Class<T> type) {
    try {
      return Optional.of(send("GET", path, null, type));
    } catch (CloudflareApiException e) {
      if (e.isNotFound()) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private void deleteIgnoringNotFound(String path) {
    try {
      execute("DELETE", path, null);
    } catch (CloudflareApiException e) {
      if (!e.isNotFound()) {
        throw e;
      }
    }
  }

  private <T> T send(String method, String path, Object body, Class<T> type) {
    JsonNode result = execute(method, path, body);
    return mapper.convertValue(result, type);
  }

  private <T> List<T> sendList(String method, String path, Class<T> type) {
    JsonNode result = execute(method, path, null);
    if (result == null || !result.isArray()) {
      return List.of();
    }
    return mapper.convertValue(
        result, mapper.getTypeFactory().constructCollectionType(List.class, type));
  }

  private JsonNode execute(String method, String path, Object body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiToken)
            .header("Accept", "application/json");
    if (body != null) {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(toJson(body)));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }

    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new CloudflareApiException(method, path, 0, List.of(), null, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CloudflareApiException(method, path, 0, List.of(), null, e);
    }

    JsonNode envelope = parse(response.body());
    boolean success = envelope != null && envelope.path("success").asBoolean(false);
    if (response.statusCode() >= 300 || !success) {
      throw new CloudflareApiException(
          method,
          path,
          response.statusCode(),
          errors(envelope),
          retryAfter(response),
          null);
    }
    return envelope.get("result");
  }

  private List<ApiError> errors(JsonNode envelope) {
    if (envelope == null || !envelope.path("errors").isArray()) {
      return List.of();
    }
    return mapper.convertValue(
        envelope.get("errors"),
        mapper.getTypeFactory().constructCollectionType(List.class, ApiError.class));
  }

  private static Duration retryAfter(HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .flatMap(
            value -> {
              try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
              } catch (NumberFormatException e) {
                return Optional.empty();
              }
            })
        .orElse(null);
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return mapper.readTree(body);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private String toJson(Object body) {
    try {
      return mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize request body", e);
    }
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
