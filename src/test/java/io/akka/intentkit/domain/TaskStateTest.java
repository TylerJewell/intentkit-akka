package io.akka.intentkit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 8, 16, 20, 21, 22, 29 — the run slot, the thirty-minute boundary, the
 * scheduling signature, and what a disabled task carries.
 *
 * <p>The boundary is walked at 29 and 31 minutes because those are the two ages probe 02
 * measured against a real partial unique index (question-log #21).
 */
public class TaskStateTest {

  private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

  private TaskState task() {
    return TaskState.empty("team-a", "k1")
        .apply(new TaskEvent.Created("team-a", "k1", "nightly", null, "*/5 * * * *",
            "do the thing", true, "agent-1", T0.plusSeconds(300)));
  }

  private Execution run(String id, Instant scheduledFor) {
    return Execution.starting(id, "k1", "agent-1", "agent-1", "autonomous-k1",
        "user-1", Trigger.CRON, null, scheduledFor, scheduledFor);
  }

  @Test
  public void anEnabledTaskWithNoStatusWaits() {
    assertThat(task().status()).isEqualTo(TaskStatus.WAITING);
  }

  @Test
  public void aDisabledTaskCarriesNoRuntimeState() {
    var disabled = task()
        .apply(new TaskEvent.RunClaimed(run("e1", T0), null, T0))
        .apply(new TaskEvent.Updated("nightly", null, "*/5 * * * *", "do the thing",
            false, "agent-1", null));
    assertThat(disabled.status()).isNull();
    assertThat(disabled.nextRunTime()).isNull();
  }

  @Test
  public void reEnablingRestoresTheWaitingStatus() {
    var again = task()
        .apply(new TaskEvent.Updated("nightly", null, "*/5 * * * *", "p", false, null, null))
        .apply(new TaskEvent.Updated("nightly", null, "*/5 * * * *", "p", true, null,
            T0.plusSeconds(300)));
    assertThat(again.status()).isEqualTo(TaskStatus.WAITING);
  }

  @Test
  public void onlyFourFieldsAreSchedulingRelevant() {
    var base = task();
    var renamed = base.apply(new TaskEvent.Updated("renamed", "a new description",
        "*/5 * * * *", "do the thing", true, "agent-1", base.nextRunTime()));
    assertThat(renamed.schedulingSignature()).isEqualTo(base.schedulingSignature());

    var recron = base.apply(new TaskEvent.Updated("nightly", null, "0 * * * *",
        "do the thing", true, "agent-1", base.nextRunTime()));
    assertThat(recron.schedulingSignature()).isNotEqualTo(base.schedulingSignature());
  }

  @Test
  public void claimingAFreeSlotIsGranted() {
    var decision = task().claim(run("e1", T0), T0);
    assertThat(decision.granted()).isTrue();
    assertThat(decision.interruptedExecutionId()).isNull();
  }

  @Test
  public void aClaimIsRefusedWhileAYoungerRunHoldsTheSlot() {
    var busy = task().apply(new TaskEvent.RunClaimed(run("e1", T0), null, T0));
    for (var age : List.of(Duration.ofMinutes(1), Duration.ofMinutes(29),
        Duration.ofMinutes(30).minusSeconds(1))) {
      var decision = busy.claim(run("e2", T0), T0.plus(age));
      assertThat(decision.granted()).as("age %s", age).isFalse();
    }
  }

  @Test
  public void aRunAbandonedForThirtyMinutesReleasesTheSlotAsInterrupted() {
    var busy = task().apply(new TaskEvent.RunClaimed(run("e1", T0), null, T0));
    for (var age : List.of(Duration.ofMinutes(30), Duration.ofMinutes(31),
        Duration.ofMinutes(120))) {
      var decision = busy.claim(run("e2", T0), T0.plus(age));
      assertThat(decision.granted()).as("age %s", age).isTrue();
      assertThat(decision.interruptedExecutionId()).isEqualTo("e1");
    }

    var after = busy.apply(new TaskEvent.RunClaimed(run("e2", T0), "e1", T0.plusSeconds(1860)));
    var interrupted = after.history().stream().filter(e -> e.id().equals("e1")).findFirst();
    assertThat(interrupted).isPresent();
    assertThat(interrupted.get().status()).isEqualTo(ExecutionStatus.ERROR);
    assertThat(interrupted.get().error()).isEqualTo("interrupted");
    assertThat(after.running().id()).isEqualTo("e2");
  }

  @Test
  public void claimingMovesTheTaskToRunningAndFinishingMovesItBack() {
    var claimed = task().apply(new TaskEvent.RunClaimed(run("e1", T0), null, T0));
    assertThat(claimed.status()).isEqualTo(TaskStatus.RUNNING);

    var ok = claimed.apply(new TaskEvent.RunFinished("e1", ExecutionStatus.SUCCESS, null,
        RunStats.empty(), T0.plusSeconds(5)));
    assertThat(ok.status()).isEqualTo(TaskStatus.WAITING);
    assertThat(ok.running()).isNull();

    var bad = claimed.apply(new TaskEvent.RunFinished("e1", ExecutionStatus.ERROR, "boom",
        RunStats.empty(), T0.plusSeconds(5)));
    assertThat(bad.status()).isEqualTo(TaskStatus.ERROR);
    assertThat(bad.history().get(0).error()).isEqualTo("boom");
  }

  @Test
  public void finishingARunThatIsNoLongerHeldChangesNothing() {
    var claimed = task().apply(new TaskEvent.RunClaimed(run("e1", T0), null, T0));
    var stale = claimed.apply(new TaskEvent.RunFinished("gone", ExecutionStatus.SUCCESS, null,
        RunStats.empty(), T0.plusSeconds(5)));
    assertThat(stale.running()).isNotNull();
    assertThat(stale.status()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  public void aDeletedTaskIsNoLongerThere() {
    var gone = task().apply(new TaskEvent.Deleted());
    assertThat(gone.exists()).isFalse();
    assertThat(gone.claim(run("e1", T0), T0).granted()).isFalse();
  }

  @Test
  public void historyIsNewestFirstAndBounded() {
    var state = task();
    for (int i = 0; i < TaskState.HISTORY_LIMIT + 5; i++) {
      var id = "e" + i;
      state = state
          .apply(new TaskEvent.RunClaimed(run(id, T0), null, T0.plusSeconds(i * 600L)))
          .apply(new TaskEvent.RunFinished(id, ExecutionStatus.SUCCESS, null,
              RunStats.empty(), T0.plusSeconds(i * 600L + 1)));
    }
    assertThat(state.history()).hasSize(TaskState.HISTORY_LIMIT);
    assertThat(state.history().get(0).id())
        .isEqualTo("e" + (TaskState.HISTORY_LIMIT + 4));
  }
}
