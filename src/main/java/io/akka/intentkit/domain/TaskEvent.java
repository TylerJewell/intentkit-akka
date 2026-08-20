package io.akka.intentkit.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

/** Everything that can happen to a scheduled task. */
public sealed interface TaskEvent {

  @TypeName("task-created")
  record Created(
      String teamId,
      String taskId,
      String name,
      String description,
      String cron,
      String prompt,
      boolean enabled,
      String targetAgentId,
      Instant nextRunTime)
      implements TaskEvent {}

  @TypeName("task-updated")
  record Updated(
      String name,
      String description,
      String cron,
      String prompt,
      boolean enabled,
      String targetAgentId,
      Instant nextRunTime)
      implements TaskEvent {}

  @TypeName("task-deleted")
  record Deleted() implements TaskEvent {}

  /**
   * The run slot taken. {@code interruptedExecutionId} names the run this claim displaced,
   * which is how a run abandoned by a crash is closed — in the same event that admits its
   * replacement, exactly where intentkit does it (question-log #22).
   */
  @TypeName("run-claimed")
  record RunClaimed(Execution execution, String interruptedExecutionId, Instant at)
      implements TaskEvent {}

  @TypeName("run-finished")
  record RunFinished(
      String executionId, ExecutionStatus status, String error, RunStats stats, Instant at)
      implements TaskEvent {}

  @TypeName("next-run-time-set")
  record NextRunTimeSet(Instant nextRunTime) implements TaskEvent {}
}
