package io.repsy.cfpo.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NamingTest {

  private static final String PROJECT_PATTERN = "^[a-z0-9]([-a-z0-9]{0,56}[a-z0-9])?$";

  @Test
  void explicitProjectNameWins() {
    assertThat(Naming.projectName("my-site", "apps", "web")).isEqualTo("my-site");
  }

  @Test
  void derivedProjectNameIsSanitized() {
    assertThat(Naming.projectName(null, "Team.Apps", "web__frontend"))
        .isEqualTo("team-apps-web-frontend");
    assertThat(Naming.projectName("  ", "apps", "web")).isEqualTo("apps-web");
  }

  @Test
  void longProjectNamesAreTruncatedWithStableHash() {
    String namespace = "a-very-long-namespace-name-for-some-team";
    String name = "and-an-even-longer-resource-name-for-the-frontend";

    String first = Naming.projectName(null, namespace, name);
    String second = Naming.projectName(null, namespace, name);

    assertThat(first).isEqualTo(second).matches(PROJECT_PATTERN);
    assertThat(first.length()).isLessThanOrEqualTo(Naming.MAX_PROJECT_NAME);
    assertThat(Naming.projectName(null, namespace, name + "-2")).isNotEqualTo(first);
  }

  @Test
  void deployHashChangesWithImageOrDirectory() {
    String base = Naming.deployHash("registry/web:1", "/dist");
    assertThat(base)
        .hasSize(Naming.HASH_LENGTH)
        .isEqualTo(Naming.deployHash("registry/web:1", "/dist"));
    assertThat(Naming.deployHash("registry/web:2", "/dist")).isNotEqualTo(base);
    assertThat(Naming.deployHash("registry/web:1", "/public")).isNotEqualTo(base);
  }

  @Test
  void jobNamesFitLabelLimit() {
    String hash = Naming.deployHash("img", "/dir");
    String longName = "x".repeat(200);

    String jobName = Naming.deployJobName("apps", longName, hash);

    assertThat(jobName.length()).isLessThanOrEqualTo(Naming.MAX_JOB_NAME);
    assertThat(jobName).endsWith("-" + hash).matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");
    assertThat(Naming.deployJobName("apps", "web", hash)).isEqualTo("apps-web-" + hash);
  }

  @Test
  void dnsCommentIsBoundedAndUniquePerResource() {
    assertThat(Naming.dnsComment("apps", "web")).isEqualTo("managed-by: cfpo apps/web");

    String longComment = Naming.dnsComment("apps", "y".repeat(200));
    assertThat(longComment.length()).isLessThanOrEqualTo(Naming.MAX_DNS_COMMENT);
    assertThat(longComment).isNotEqualTo(Naming.dnsComment("apps", "z".repeat(200)));
  }
}
