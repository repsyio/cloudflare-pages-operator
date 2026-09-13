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
  private static final String COMMENT_PREFIX = "managed-by: cfpo ";

  private Naming() {}

  public static String projectName(String explicitName, String namespace, String name) {
    if (explicitName != null && !explicitName.isBlank()) {
      return explicitName;
    }
    return truncateWithHash(sanitize(namespace + "-" + name), MAX_PROJECT_NAME);
  }

  /** Changes whenever the deployed content source changes. */
  public static String deployHash(String image, String directory) {
    return sha256(image + "\n" + directory).substring(0, HASH_LENGTH);
  }

  public static String deployJobName(String namespace, String name, String deployHash) {
    String base =
        truncateWithHash(sanitize(namespace + "-" + name), MAX_JOB_NAME - HASH_LENGTH - 1);
    return base + "-" + deployHash;
  }

  /** Marks DNS records created by this operator for one specific resource. */
  public static String dnsComment(String namespace, String name) {
    String comment = COMMENT_PREFIX + namespace + "/" + name;
    if (comment.length() <= MAX_DNS_COMMENT) {
      return comment;
    }
    return COMMENT_PREFIX + sha256(namespace + "/" + name).substring(0, 32);
  }

  public static String pagesDevHost(String projectName) {
    return projectName + ".pages.dev";
  }

  static String sanitize(String value) {
    String cleaned =
        value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
    return cleaned.isEmpty() ? "site" : cleaned;
  }

  static String truncateWithHash(String value, int maxLength) {
    if (value.length() <= maxLength) {
      return value;
    }
    String hash = sha256(value).substring(0, 8);
    String prefix = value.substring(0, maxLength - hash.length() - 1).replaceAll("-+$", "");
    return prefix + "-" + hash;
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
