package io.repsy.cfpo.cloudflare.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A custom domain attached to a Pages project. {@code status} is one of initializing, pending,
 * active, deactivated, blocked or error.
 */
public record PagesDomain(
    String id,
    String name,
    String status,
    @JsonProperty("validation_data") Validation validationData) {

  public static final String STATUS_ACTIVE = "active";

  public boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }

  public boolean isFailed() {
    return "error".equals(status) || "blocked".equals(status) || "deactivated".equals(status);
  }

  public String errorMessage() {
    return validationData == null ? null : validationData.errorMessage();
  }

  public record Validation(String status, @JsonProperty("error_message") String errorMessage) {}
}
