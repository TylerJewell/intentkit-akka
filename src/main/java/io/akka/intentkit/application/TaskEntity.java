package io.akka.intentkit.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.intentkit.domain.Execution;
import io.akka.intentkit.domain.ExecutionStatus;
import io.akka.intentkit.domain.RunStats;
import io.akka.intentkit.domain.TaskEvent;
import io.akka.intentkit.domain.TaskState;
import java.time.Instant;
import java.util.List;

/**
 * One scheduled task — SPEC-001 rules 8, 16, 20, 21, 22, 29.
 *
 * <p>The entity id is {@code teamId-taskId}, which is also the job id intentkit gives the
 * scheduler (question-log #11). All the rules live in {@link TaskState}, so they can be tested
 * without a runtime and are tested that way in {@code TaskStateTest}; this class decides only
 * what to persist and what to answer.
 */
@Component(id = "task")
public class TaskEntity extends EventSourcedEntity<TaskState, TaskEvent> {

  private final String jobId;

  public TaskEntity(EventSourcedEntityContext context) {
    this.jobId = context.entityId();
  }

  @Override
  public TaskState emptyState() {
    // The entity id is the job id, and a task id may itself contain dashes, so the two
    // halves are carried on the create command rather than split back out of the id.
    return TaskState.empty(null, null);
  }

  public record Create(
      String teamId,
      String taskId,
      String name,
      String description,
      String cron,
      String prompt,
      boolean enabled,
      String targetAgentId,
      Instant nextRunTime) {}

  public record Update(
      String name,
      String description,
      String cron,
      String prompt,
      boolean enabled,
      String targetAgentId,
      Instant nextRunTime) {}

  public record ClaimRequest(Execution execution, Instant now) {}

  public record ClaimReply(boolean granted, String refusal, Execution execution) {}

  public record FinishRequest(
      String executionId, ExecutionStatus status, String error, RunStats stats, Instant at) {}

  public Effect<Done> create(Create command) {
    if (currentState().exists()) {
      return effects().error("Task '" + jobId + "' already exists");
    }
    return effects()
        .persist(new TaskEvent.Created(command.teamId(), command.taskId(), command.name(),
            command.description(),
            command.cron(), command.prompt(), command.enabled(), command.targetAgentId(),
            command.nextRunTime()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> update(Update command) {
    if (!currentState().exists()) {
      return effects().error("Autonomous task with ID " + jobId + " not found.");
    }
    return effects()
        .persist(new TaskEvent.Updated(command.name(), command.description(), command.cron(),
            command.prompt(), command.enabled(), command.targetAgentId(), command.nextRunTime()))
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> delete() {
    if (!currentState().exists()) {
      return effects().error("Autonomous task with ID " + jobId + " not found.");
    }
    return effects().persist(new TaskEvent.Deleted()).thenReply(state -> Done.getInstance());
  }

  /** Wrapped rather than a bare Instant, because "no next run" is one of the answers. */
  public record NextRun(Instant at) {}

  public Effect<Done> setNextRunTime(NextRun nextRun) {
    if (!currentState().exists()) {
      return effects().error("Autonomous task with ID " + jobId + " not found.");
    }
    return effects()
        .persist(new TaskEvent.NextRunTimeSet(nextRun.at()))
        .thenReply(state -> Done.getInstance());
  }

  /**
   * Take the run slot, or say why not.
   *
   * <p>A refusal is a reply rather than an error: a scheduled fire that arrives while a run
   * is in progress is a normal, expected skip, and turning it into a command failure would
   * make the runtime re-deliver it (question-log T4).
   */
  public Effect<ClaimReply> claim(ClaimRequest request) {
    var decision = currentState().claim(request.execution(), request.now());
    if (!decision.granted()) {
      return effects().reply(new ClaimReply(false, decision.refusal(), null));
    }
    return effects()
        .persist(new TaskEvent.RunClaimed(request.execution(), decision.interruptedExecutionId(),
            request.now()))
        .thenReply(state -> new ClaimReply(true, null, state.running()));
  }

  public Effect<Done> finish(FinishRequest request) {
    if (currentState().running() == null
        || !currentState().running().id().equals(request.executionId())) {
      // The task, and with it the run, was deleted while the run was in flight. intentkit
      // answers None here rather than failing (question-log #24).
      return effects().reply(Done.getInstance());
    }
    return effects()
        .persist(new TaskEvent.RunFinished(request.executionId(), request.status(),
            request.error(), request.stats(), request.at()))
        .thenReply(state -> Done.getInstance());
  }

  public ReadOnlyEffect<TaskState> get() {
    return effects().reply(currentState());
  }

  public ReadOnlyEffect<List<Execution>> executions() {
    return effects().reply(currentState().history());
  }

  @Override
  public TaskState applyEvent(TaskEvent event) {
    return currentState().apply(event);
  }
}
