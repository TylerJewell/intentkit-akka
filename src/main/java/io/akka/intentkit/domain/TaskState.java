package io.akka.intentkit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One scheduled task: its configuration, its runtime status, and its run slot.
 *
 * <p>SPEC-001 rules 8, 16, 20, 21, 22, 29. The run slot is the whole of the overlap rule —
 * intentkit buys it with a partial unique index on the executions table, and this port gets
 * it from the entity's single writer, which was measured granting exactly one of sixteen
 * simultaneous claims (question-log T3).
 */
public record TaskState(
    String teamId,
    String taskId,
    String name,
    String description,
    String cron,
    String prompt,
    boolean enabled,
    String targetAgentId,
    TaskStatus status,
    Instant nextRunTime,
    Execution running,
    List<Execution> history,
    boolean exists) {

  /** A run still open after this long is treated as abandoned by a crashed process. */
  public static final Duration STALE_AFTER = Duration.ofMinutes(30);

  /** How many finished runs are kept. Beyond this the oldest are dropped. */
  public static final int HISTORY_LIMIT = 50;

  public static TaskState empty(String teamId, String taskId) {
    return new TaskState(teamId, taskId, null, null, null, null, false, null,
        null, null, null, List.of(), false);
  }

  public String jobId() {
    return teamId + "-" + taskId;
  }

  /**
   * The four fields a change to which means the schedule has to be re-armed.
   *
   * <p>Exactly intentkit's own four (question-log #12). Runtime status is deliberately not
   * among them: writing it bumps the row's timestamp, and keying on that churned the
   * scheduler every sweep.
   */
  public String schedulingSignature() {
    return cron + "|" + prompt + "|" + enabled + "|" + targetAgentId;
  }

  /** Whether the slot is free, and which abandoned run has to be closed to free it. */
  public ClaimDecision claim(Execution candidate, Instant now) {
    if (!exists) {
      return new ClaimDecision(false, null, "Task not found");
    }
    if (running == null) {
      return new ClaimDecision(true, null, null);
    }
    if (now.isBefore(running.startedAt().plus(STALE_AFTER))) {
      return new ClaimDecision(false, null, "Task is currently running, try again later.");
    }
    return new ClaimDecision(true, running.id(), null);
  }

  public TaskState apply(TaskEvent event) {
    return switch (event) {
      case TaskEvent.Created e -> normalise(new TaskState(
          e.teamId(), e.taskId(), e.name(), e.description(), e.cron(), e.prompt(),
          e.enabled(), e.targetAgentId(), TaskStatus.WAITING, e.nextRunTime(),
          null, List.of(), true));
      case TaskEvent.Updated e -> normalise(new TaskState(
          teamId, taskId, e.name(), e.description(), e.cron(), e.prompt(), e.enabled(),
          e.targetAgentId(), status, e.nextRunTime(), running, history, exists));
      case TaskEvent.Deleted e -> empty(teamId, taskId);
      case TaskEvent.RunClaimed e -> claimed(e);
      case TaskEvent.RunFinished e -> finished(e);
      case TaskEvent.NextRunTimeSet e -> normalise(new TaskState(
          teamId, taskId, name, description, cron, prompt, enabled, targetAgentId,
          status, e.nextRunTime(), running, history, exists));
    };
  }

  private TaskState claimed(TaskEvent.RunClaimed event) {
    var newHistory = history;
    if (event.interruptedExecutionId() != null && running != null
        && running.id().equals(event.interruptedExecutionId())) {
      newHistory = prepend(
          running.finished(ExecutionStatus.ERROR, "interrupted", RunStats.empty(), event.at()),
          history);
    }
    return new TaskState(teamId, taskId, name, description, cron, prompt, enabled,
        targetAgentId, TaskStatus.RUNNING, nextRunTime, event.execution(), newHistory, exists);
  }

  private TaskState finished(TaskEvent.RunFinished event) {
    if (running == null || !running.id().equals(event.executionId())) {
      return this;
    }
    var done = running.finished(event.status(), event.error(), event.stats(), event.at());
    var next = event.status() == ExecutionStatus.SUCCESS ? TaskStatus.WAITING : TaskStatus.ERROR;
    return normalise(new TaskState(teamId, taskId, name, description, cron, prompt, enabled,
        targetAgentId, next, nextRunTime, null, prepend(done, history), exists));
  }

  /**
   * A disabled task carries no runtime state, and an enabled one always carries a status.
   *
   * <p>Applied on every transition rather than at the call sites, so a caller cannot ask for
   * a combination that does not exist (question-log #8).
   */
  private static TaskState normalise(TaskState state) {
    if (!state.exists()) {
      return state;
    }
    if (!state.enabled()) {
      return new TaskState(state.teamId(), state.taskId(), state.name(), state.description(),
          state.cron(), state.prompt(), false, state.targetAgentId(), null, null,
          state.running(), state.history(), true);
    }
    if (state.status() == null) {
      return new TaskState(state.teamId(), state.taskId(), state.name(), state.description(),
          state.cron(), state.prompt(), true, state.targetAgentId(), TaskStatus.WAITING,
          state.nextRunTime(), state.running(), state.history(), true);
    }
    return state;
  }

  private static List<Execution> prepend(Execution execution, List<Execution> history) {
    var out = new ArrayList<Execution>(Math.min(history.size() + 1, HISTORY_LIMIT));
    out.add(execution);
    for (int i = 0; i < history.size() && out.size() < HISTORY_LIMIT; i++) {
      out.add(history.get(i));
    }
    return List.copyOf(out);
  }

  /** Whether the slot was granted, what it displaced, and why not when it was refused. */
  public record ClaimDecision(boolean granted, String interruptedExecutionId, String refusal) {}
}
