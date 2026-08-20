package io.akka.intentkit.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One run of a task.
 *
 * <p>{@code scheduledFor} is this port's addition — intentkit records only when a run
 * started, so how late it was is not a number anyone can read. SPEC-001 rule 30, decision 3.
 */
public record Execution(
    String id,
    String taskId,
    String agentId,
    String targetAgentId,
    String chatId,
    String runAsUserId,
    Trigger trigger,
    String triggeredBy,
    ExecutionStatus status,
    String error,
    String result,
    long inputTokens,
    long outputTokens,
    long cachedInputTokens,
    BigDecimal creditCost,
    int messageCount,
    double coldStartCost,
    Instant scheduledFor,
    Instant startedAt,
    Instant finishedAt) {

  public static Execution starting(
      String id,
      String taskId,
      String agentId,
      String targetAgentId,
      String chatId,
      String runAsUserId,
      Trigger trigger,
      String triggeredBy,
      Instant scheduledFor,
      Instant startedAt) {
    return new Execution(
        id, taskId, agentId, targetAgentId, chatId, runAsUserId, trigger, triggeredBy,
        ExecutionStatus.RUNNING, null, null, 0, 0, 0, null, 0, 0.0,
        scheduledFor, startedAt, null);
  }

  public Execution finished(ExecutionStatus status, String error, RunStats stats, Instant at) {
    return new Execution(
        id, taskId, agentId, targetAgentId, chatId, runAsUserId, trigger, triggeredBy,
        status, error, stats.result(), stats.inputTokens(), stats.outputTokens(),
        stats.cachedInputTokens(), stats.creditCost(), stats.messageCount(),
        stats.coldStartCost(), scheduledFor, startedAt, at);
  }

  /** How late the run was against the moment it was scheduled for. */
  public long latenessMillis() {
    if (scheduledFor == null || startedAt == null) {
      return 0;
    }
    return java.time.Duration.between(scheduledFor, startedAt).toMillis();
  }
}
