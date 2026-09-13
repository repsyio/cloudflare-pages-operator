package io.repsy.cfpo.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.repsy.cfpo.config.OperatorConfig;
import io.repsy.cfpo.config.OperatorConfigTest;
import io.repsy.cfpo.crd.CloudflarePage;
import io.repsy.cfpo.crd.CloudflarePageSpec;

class DeployJobFactoryTest {

  static CloudflarePage page(String directory) {
    CloudflarePageSpec spec = new CloudflarePageSpec();
    spec.setImage("registry.example.com/web:1.2.3");
    spec.setDirectory(directory);
    spec.setDomain("app.example.com");
    CloudflarePage page = new CloudflarePage();
    page.setMetadata(new ObjectMetaBuilder().withNamespace("apps").withName("web").withUid("uid-1").build());
    page.setSpec(spec);
    return page;
  }

  private final OperatorConfig config;
  private final DeployJobFactory factory;

  DeployJobFactoryTest() {
    Map<String, String> env = OperatorConfigTest.requiredEnv();
    env.put("DEPLOY_PULL_SECRETS", "regcred");
    config = OperatorConfig.from(env);
    factory = new DeployJobFactory(config);
  }

  @Test
  void buildsJobInOperatorNamespaceWithOwnerMetadata() {
    Job job = factory.build(page("/usr/share/nginx/html"), "apps-web", "apps-web-abc", "abc");

    assertThat(job.getMetadata().getNamespace()).isEqualTo("cfpo-system");
    assertThat(job.getMetadata().getLabels())
        .containsEntry(DeployJobFactory.MANAGED_BY_LABEL, "cfpo")
        .containsEntry(DeployJobFactory.OWNER_UID_LABEL, "uid-1")
        .containsEntry(DeployJobFactory.DEPLOY_HASH_LABEL, "abc");
    assertThat(DeployJobFactory.ownerOf(job)).containsExactly(new ResourceID("web", "apps"));
    assertThat(job.getSpec().getBackoffLimit()).isEqualTo(DeployJobFactory.BACKOFF_LIMIT);
    assertThat(job.getSpec().getTtlSecondsAfterFinished()).isEqualTo(3600);

    PodSpec pod = job.getSpec().getTemplate().getSpec();
    assertThat(pod.getRestartPolicy()).isEqualTo("Never");
    assertThat(pod.getAutomountServiceAccountToken()).isFalse();
    assertThat(pod.getImagePullSecrets()).extracting("name").containsExactly("regcred");
  }

  @Test
  void copyStepPassesDirectoryOnlyThroughEnvironment() {
    String hostile = "/dist\"; rm -rf / #";
    Job job = factory.build(page(hostile), "apps-web", "apps-web-abc", "abc");

    Container copy = job.getSpec().getTemplate().getSpec().getInitContainers().getFirst();
    assertThat(copy.getImage()).isEqualTo("registry.example.com/web:1.2.3");
    assertThat(copy.getImagePullPolicy()).isNull();
    assertThat(copy.getCommand()).containsExactly("sh", "-c", DeployJobFactory.COPY_SCRIPT);
    assertThat(copy.getCommand().get(2)).doesNotContain("rm -rf");
    assertThat(copy.getEnv()).extracting(EnvVar::getName, EnvVar::getValue).containsExactly(org.assertj.core.groups.Tuple.tuple("SOURCE_DIR", hostile));
    assertThat(copy.getSecurityContext().getRunAsNonRoot()).isTrue();
  }

  @Test
  void deployStepRunsWranglerWithSecretToken() {
    CloudflarePage page = page("/dist");
    page.getSpec().setBranch("production");
    page.getSpec().setRevision("2");

    Job job = factory.build(page, "apps-web", "apps-web-abc", "abc");

    PodSpec pod = job.getSpec().getTemplate().getSpec();
    assertThat(pod.getInitContainers().getFirst().getImagePullPolicy()).isEqualTo("Always");
    Container deploy = pod.getContainers().getFirst();
    assertThat(deploy.getImage()).isEqualTo("cfpo-deployer:1");
    assertThat(deploy.getArgs())
        .startsWith("pages", "deploy", DeployJobFactory.SITE_PATH)
        .contains("--project-name=apps-web", "--branch=production");
    EnvVar token = deploy.getEnv().stream().filter(e -> e.getName().equals("CLOUDFLARE_API_TOKEN")).findFirst().orElseThrow();
    assertThat(token.getValue()).isNull();
    assertThat(token.getValueFrom().getSecretKeyRef().getName()).isEqualTo("cfpo");
    assertThat(token.getValueFrom().getSecretKeyRef().getKey()).isEqualTo("api-token");
    assertThat(deploy.getEnv()).extracting(EnvVar::getName).doesNotContain("CLOUDFLARE_API_BASE_URL");
    assertThat(deploy.getVolumeMounts().getFirst().getReadOnly()).isTrue();
  }

  @Test
  void jobStatesReadConditionsAndContainerOutput() {
    Job running = new JobBuilder().withNewStatus().withActive(1).endStatus().build();
    Job complete =
        new JobBuilder().withNewStatus().addNewCondition().withType("Complete").withStatus("True").endCondition().endStatus().build();
    Job failed =
        new JobBuilder()
            .withNewStatus()
            .addNewCondition()
            .withType("Failed")
            .withStatus("True")
            .withReason("BackoffLimitExceeded")
            .withMessage("Job has reached the specified backoff limit")
            .endCondition()
            .endStatus()
            .build();

    assertThat(JobStates.finished(running)).isFalse();
    assertThat(JobStates.succeeded(complete)).isTrue();
    assertThat(JobStates.failed(failed)).isTrue();
    assertThat(JobStates.describeFailure(failed, List.of())).contains("BackoffLimitExceeded");

    var pod =
        new PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus()
            .withInitContainerStatuses(
                new ContainerStatusBuilder()
                    .withName("copy-site")
                    .withNewState()
                    .withNewTerminated()
                    .withExitCode(2)
                    .withMessage("directory /dist does not exist in the image\n")
                    .endTerminated()
                    .endState()
                    .build())
            .endStatus()
            .build();
    assertThat(JobStates.describeFailure(failed, List.of(pod)))
        .isEqualTo("container copy-site exited with code 2: directory /dist does not exist in the image");
  }
}
