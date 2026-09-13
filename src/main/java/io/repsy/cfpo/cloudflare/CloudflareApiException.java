package io.repsy.cfpo.cloudflare;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import io.repsy.cfpo.cloudflare.model.ApiError;

/** A failed Cloudflare API call. {@code status} is 0 when no HTTP response was received. */
public class CloudflareApiException extends RuntimeException {

  private final int status;
  private final List<ApiError> errors;
  private final Duration retryAfter;

  public CloudflareApiException(
      String method,
      String path,
      int status,
      List<ApiError> errors,
      Duration retryAfter,
      Throwable cause) {
    super(message(method, path, status, errors, cause), cause);
    this.status = status;
    this.errors = List.copyOf(errors);
    this.retryAfter = retryAfter;
  }

  public int status() {
    return status;
  }

  public List<ApiError> errors() {
    return errors;
  }

  /** Server-provided back-off hint, or {@code null}. */
  public Duration retryAfter() {
    return retryAfter;
  }

  public boolean isNotFound() {
    return status == 404;
  }

  public boolean isRateLimited() {
    return status == 429;
  }

  /** Errors worth retrying without a spec change: network failures, throttling, server errors. */
  public boolean isTransient() {
    return status == 0 || status == 429 || status >= 500;
  }

  private static String message(
      String method, String path, int status, List<ApiError> errors, Throwable cause) {
    String pathWithoutQuery = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
    String detail =
        errors.isEmpty()
            ? (cause != null ? String.valueOf(cause.getMessage()) : "no error details")
            : errors.stream()
                .map(e -> e.code() + ": " + e.message())
                .collect(Collectors.joining("; "));
    String statusText = status == 0 ? "no response" : "HTTP " + status;
    return "Cloudflare " + method + " " + pathWithoutQuery + " failed (" + statusText + "): " + detail;
  }
}
