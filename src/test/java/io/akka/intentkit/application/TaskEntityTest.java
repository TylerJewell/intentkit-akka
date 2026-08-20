package io.akka.intentkit.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.intentkit.domain.Execution;
import io.akka.intentkit.domain.ExecutionStatus;
import io.akka.intentkit.domain.RunStats;
import io.akka.intentkit.domain.TaskEvent;
import io.akka.intentkit.domain.TaskState;
import io.akka.intentkit.domain.TaskStatus;
import io.akka.intentkit.domain.Trigger;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 20, 21, 22, 29 as a caller sees them.
 *
 * <p>{@code TaskStateTest} checks the same rules on the state machine. What is checked here
 * and not there is that a refused claim reaches the caller as an answer rather than as a
 * command failure — a scheduled fire arriving during a run is an expected skip, and a
 * failure would make the runtime deliver it again (question-log T4).
 */
public class TaskEntityTest {

  private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

  private EventSourcedTestKit<TaskState, TaskEvent, TaskEntity> task() {
    var kit = EventSourcedTestKit.of("team-a-k1", TaskEntity::new);
    kit.method(TaskEntity::create)
        .invoke(new TaskEntity.Create("team-a", "k1", "nightly", null, "*/5 * * * *",
            "do the thing", true, "agent-1", T0.plusSeconds(300)));
    return kit;
  }

  private Execution run(String id, Instant at) {
    return Execution.starting(id, "k1", "agent-1", "agent-1", "autonomous-k1", "user-1",
        Trigger.CRON, null, at, at);
  }

  @Test
  public void aRefusedClaimIsAnAnswerNotAFailure() {
    var kit = task();
    kit.method(TaskEntity::claim).invoke(new TaskEntity.ClaimRequest(run("e1", T0), T0));

    var result = kit.method(TaskEntity::claim)
        .invoke(new TaskEntity.ClaimRequest(run("e2", T0), T0.plusSeconds(60)));
    assertThat(result.isError()).isFalse();
    assertThat(result.getReply().granted()).isFalse();
    assertThat(result.getReply().refusal()).isEqualTo("Task is currently running, try again later.");
    assertThat(result.didPersistEvents()).isFalse();
  }

  @Test
  public void aClaimOnAFreeSlotPersistsExactlyOneEvent() {
    var kit = task();
    var result = kit.method(TaskEntity::claim)
        .invoke(new TaskEntity.ClaimRequest(run("e1", T0), T0));
    assertThat(result.getReply().granted()).isTrue();
    assertThat(result.getNextEventOfType(TaskEvent.RunClaimed.class).interruptedExecutionId())
        .isNull();
    assertThat(kit.getState().status()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  public void anAbandonedRunIsClosedByTheClaimThatDisplacesIt() {
    var kit = task();
    kit.method(TaskEntity::claim).invoke(new TaskEntity.ClaimRequest(run("e1", T0), T0));

    var late = T0.plus(Duration.ofMinutes(31));
    var result = kit.method(TaskEntity::claim)
        .invoke(new TaskEntity.ClaimRequest(run("e2", late), late));
    assertThat(result.getReply().granted()).isTrue();
    assertThat(result.getNextEventOfType(TaskEvent.RunClaimed.class).interruptedExecutionId())
        .isEqualTo("e1");
    assertThat(kit.getState().history().get(0).error()).isEqualTo("interrupted");
  }

  @Test
  public void finishingARunThatIsNoLongerHeldIsAcceptedAndPersistsNothing() {
    var kit = task();
    kit.method(TaskEntity::claim).invoke(new TaskEntity.ClaimRequest(run("e1", T0), T0));

    var result = kit.method(TaskEntity::finish)
        .invoke(new TaskEntity.FinishRequest("gone", ExecutionStatus.SUCCESS, null,
            RunStats.empty(), T0.plusSeconds(5)));
    assertThat(result.isError()).isFalse();
    assertThat(result.didPersistEvents()).isFalse();
  }

  @Test
  public void creatingTheSameTaskTwiceIsRefused() {
    var kit = task();
    var again = kit.method(TaskEntity::create)
        .invoke(new TaskEntity.Create("team-a", "k1", null, null, "*/5 * * * *", "p",
            true, null, null));
    assertThat(again.isError()).isTrue();
  }

  @Test
  public void everyCommandOnAMissingTaskIsRefused() {
    var kit = EventSourcedTestKit.of("team-a-nope", TaskEntity::new);
    assertThat(kit.method(TaskEntity::delete).invoke().isError()).isTrue();
    assertThat(kit.method(TaskEntity::setNextRunTime)
        .invoke(new TaskEntity.NextRun(T0)).isError()).isTrue();
    assertThat(kit.method(TaskEntity::update)
        .invoke(new TaskEntity.Update(null, null, "*/5 * * * *", "p", true, null, null))
        .isError()).isTrue();
    // A claim is still an answer rather than a failure: a fire for a deleted task is a
    // normal thing to happen, and the timer deletes itself rather than being retried.
    var claim = kit.method(TaskEntity::claim)
        .invoke(new TaskEntity.ClaimRequest(run("e1", T0), T0));
    assertThat(claim.isError()).isFalse();
    assertThat(claim.getReply().granted()).isFalse();
  }
}
