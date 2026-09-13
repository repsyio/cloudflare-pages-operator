package io.repsy.cfpo.reconciler;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;

/** Reads the outcome of deploy Jobs. */
final class JobStates {

  static final int MAX_MESSAGE_LENGTH = 1024;

  private JobStates() {}

  static boolean succeeded(Job job) {
    if (hasCondition(job, "Complete")) {
      return true;
    }
    return job.getStatus() != null
        && job.getStatus().getSucceeded() != null
        && job.getStatus().getSucceeded() > 0;
  }

  static boolean failed(Job job) {
    return hasCondition(job, "Failed");
  }

  static boolean finished(Job job) {
    return succeeded(job) || failed(job);
  }

  /**
   * Explains a failed Job, preferring the output of the failing container (its termination message
   * falls back to the log tail) over the Job's generic condition message.
   */
  static String describeFailure(Job job, List<Pod> pods) {
    Optional<String> containerFailure =
        pods.stream()
            .sorted(
                Comparator.comparing(
                        (Pod p) -> p.getMetadata().getCreationTimestamp(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .reversed())
            .flatMap(JobStates::failedContainers)
            .findFirst();
    if (containerFailure.isPresent()) {
      return truncate(containerFailure.get());
    }
    return condition(job, "Failed")
        .map(c -> truncate("Deploy job failed: " + c.getReason() + ": " + c.getMessage()))
        .orElse("Deploy job failed");
  }

  private static Stream<String> failedContainers(Pod pod) {
    if (pod.getStatus() == null) {
      return Stream.empty();
    }
    return Stream.concat(
            Stream.ofNullable(pod.getStatus().getInitContainerStatuses()).flatMap(List::stream),
            Stream.ofNullable(pod.getStatus().getContainerStatuses()).flatMap(List::stream))
        .map(JobStates::failureOf)
        .filter(Objects::nonNull);
  }

  private static String failureOf(ContainerStatus status) {
    if (status.getState() != null && status.getState().getWaiting() != null) {
      String reason = status.getState().getWaiting().getReason();
      if ("ErrImagePull".equals(reason) || "ImagePullBackOff".equals(reason)) {
        return "container " + status.getName() + ": " + reason + ": "
            + status.getState().getWaiting().getMessage();
      }
    }
    ContainerStateTerminated terminated =
        status.getState() == null ? null : status.getState().getTerminated();
    if (terminated == null || terminated.getExitCode() == null || terminated.getExitCode() == 0) {
      return null;
    }
    String output = terminated.getMessage() == null ? "" : terminated.getMessage().strip();
    return "container " + status.getName() + " exited with code " + terminated.getExitCode()
        + (output.isEmpty() ? "" : ": " + output);
  }

  private static boolean hasCondition(Job job, String type) {
    return condition(job, type).isPresent();
  }

  private static Optional<JobCondition> condition(Job job, String type) {
    if (job.getStatus() == null || job.getStatus().getConditions() == null) {
      return Optional.empty();
    }
    return job.getStatus().getConditions().stream()
        .filter(c -> type.equals(c.getType()) && "True".equals(c.getStatus()))
        .findFirst();
  }

  /** Keeps the end of long messages, where tool output usually has the actual error. */
  private static String truncate(String message) {
    if (message.length() <= MAX_MESSAGE_LENGTH) {
      return message;
    }
    return "..." + message.substring(message.length() - (MAX_MESSAGE_LENGTH - 3));
  }
}
