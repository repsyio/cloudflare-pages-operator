package io.repsy.cfpo.reconciler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Deterministic names for the Cloudflare and Kubernetes objects the operator owns. */
public final class Naming {

  /** Cloudflare Pages project names: lowercase letters, digits and dashes, at most 58 chars. */
  public static final int MAX_PROJECT_NAME = 58;

  /** Job names end up in the {@code job-name} pod label, which is limited to 63 chars. */
  public static final int MAX_JOB_NAME = 63;

  /** Cloudflare DNS record comments are limited to 100 chars on the smallest plans. */
  public static final int MAX_DNS_COMMENT = 100;

  static final int HASH_LENGTH = 10;
  private static final int DNS_COMMENT_HASH_LENGTH = 32;
  private static final String COMMENT_PREFIX = "managed-by: cfpo ";

  private Naming() {}

  public static String projectName(
      final String explicitName, final String namespace, final String name) {
    if (explicitName != null && !explicitName.isBlank()) {
      return explicitName;
    }
    return truncateWithHash(sanitize(namespace + "-" + name), MAX_PROJECT_NAME);
  }

  /** Changes whenever the deployed content source changes. */
  public static String deployHash(final String image, final String directory) {
    return sha256(image + "\n" + directory).substring(0, HASH_LENGTH);
  }

  public static String deployJobName(
      final String namespace, final String name, final String deployHash) {
    final String base =
        truncateWithHash(sanitize(namespace + "-" + name), MAX_JOB_NAME - HASH_LENGTH - 1);
    return base + "-" + deployHash;
  }

  /** Marks DNS records created by this operator for one specific resource. */
  public static String dnsComment(final String namespace, final String name) {
    final String comment = COMMENT_PREFIX + namespace + "/" + name;
    if (comment.length() <= MAX_DNS_COMMENT) {
      return comment;
    }
    return COMMENT_PREFIX + sha256(namespace + "/" + name).substring(0, DNS_COMMENT_HASH_LENGTH);
  }

  public static String pagesDevHost(final String projectName) {
    return projectName + ".pages.dev";
  }

  static String sanitize(final String value) {
    final String cleaned =
        value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
    return cleaned.isEmpty() ? "site" : cleaned;
  }

  static String truncateWithHash(final String value, final int maxLength) {
    if (value.length() <= maxLength) {
      return value;
    }
    final String hash = sha256(value).substring(0, 8);
    final String prefix = value.substring(0, maxLength - hash.length() - 1).replaceAll("-+$", "");
    return prefix + "-" + hash;
  }

  static String sha256(final String value) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
