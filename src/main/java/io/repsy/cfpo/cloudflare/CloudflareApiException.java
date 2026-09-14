package io.repsy.cfpo.cloudflare;

import io.repsy.cfpo.cloudflare.model.ApiError;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/** A failed Cloudflare API call. {@code status} is 0 when no HTTP response was received. */
public class CloudflareApiException extends RuntimeException {

  private static final int NOT_FOUND = 404;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int SERVER_ERROR = 500;

  private final int status;
  private final List<ApiError> errors;
  private final Duration retryAfter;

  public CloudflareApiException(
      final String method,
      final String path,
      final int status,
      final List<ApiError> errors,
      final Duration retryAfter,
      final Throwable cause) {
    super(message(method, path, status, errors, cause), cause);
    this.status = status;
    this.errors = List.copyOf(errors);
    this.retryAfter = retryAfter;
  }

  public int status() {
    return this.status;
  }

  public List<ApiError> errors() {
    return this.errors;
  }

  /** Server-provided back-off hint, or {@code null}. */
  public Duration retryAfter() {
    return this.retryAfter;
  }

  public boolean isNotFound() {
    return this.status == NOT_FOUND;
  }

  public boolean isRateLimited() {
    return this.status == TOO_MANY_REQUESTS;
  }

  /** Errors worth retrying without a spec change: network failures, throttling, server errors. */
  public boolean isTransient() {
    return this.status == 0 || this.status == TOO_MANY_REQUESTS || this.status >= SERVER_ERROR;
  }

  private static String message(
      final String method,
      final String path,
      final int status,
      final List<ApiError> errors,
      final Throwable cause) {
    final String pathWithoutQuery =
        path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
    final String detail =
        errors.isEmpty()
            ? (cause != null ? String.valueOf(cause.getMessage()) : "no error details")
            : errors.stream()
                .map(e -> e.code() + ": " + e.message())
                .collect(Collectors.joining("; "));
    final String statusText = status == 0 ? "no response" : "HTTP " + status;
    return "Cloudflare "
        + method
        + " "
        + pathWithoutQuery
        + " failed ("
        + statusText
        + "): "
        + detail;
  }
}
