package io.akka.intentkit.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.intentkit.domain.CronSchedule;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Arming and cancelling a task's timer — SPEC-001 rules 9-13, 18.
 *
 * <p>The target has single timers only, so a repeating schedule is a timer that re-arms
 * itself (question-log T1). Re-arming reuses the timer name, which replaces the pending
 * one rather than adding a second (T2).
 *
 * <p>intentkit keeps its jobs in step with a sweep that reads every task once a minute.
 * This port arms a task's timer when the task is written, and evaluates the sweep's three
 * prune conditions when the timer fires instead. SPEC-001 decision 2.
 */
public final class TaskScheduler {

  static final String TIMER_PREFIX = "task-";
  static final String MANUAL_PREFIX = "manual-";

  /**
   * The measured ceiling on a timer's payload.
   *
   * <p>Above roughly this size the runtime accepts the timer at registration and then never
   * delivers it, with no error at either end: 900 bytes was delivered and 1000 was silent
   * (question-log T5, T6). A schedule that silently never fires is the worst failure this
   * component has, so the payload is checked rather than trusted.
   */
  public static final int MAX_TIMER_PAYLOAD_BYTES = 900;

  private final TimerScheduler timers;
  private final ComponentClient componentClient;

  public TaskScheduler(TimerScheduler timers, ComponentClient componentClient) {
    this.timers = timers;
    this.componentClient = componentClient;
  }

  /**
   * Arm the next fire of {@code jobId}, computed from {@code from} rather than from now, so a
   * run that takes longer than its interval does not push the schedule along with it.
   *
   * @return the moment armed for, or empty when the schedule has no future fire
   */
  public Optional<Instant> arm(String jobId, CronSchedule schedule, Instant from) {
    var next = schedule.nextAfter(from);
    next.ifPresent(
        at -> {
          var delay = Duration.between(Instant.now(), at);
          timers.createSingleTimer(
              TIMER_PREFIX + jobId,
              delay.isNegative() ? Duration.ZERO : delay,
              componentClient
                  .forTimedAction()
                  .method(TaskTimer::fire)
                  .deferred(payload(jobId)));
        });
    return next;
  }

  /** A manual run: the same persisted mechanism, with no delay. SPEC-001 decision 8. */
  public void armManual(String jobId, String userId) {
    var arg = payload(jobId + "|" + userId);
    timers.createSingleTimer(
        MANUAL_PREFIX + jobId,
        Duration.ZERO,
        componentClient.forTimedAction().method(TaskTimer::fireManual).deferred(arg));
  }

  public void cancel(String jobId) {
    timers.delete(TIMER_PREFIX + jobId);
  }

  private static String payload(String value) {
    int bytes = value.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_TIMER_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "timer payload is " + bytes + " bytes, above the " + MAX_TIMER_PAYLOAD_BYTES
              + "-byte ceiling past which a timer is accepted and never delivered");
    }
    return value;
  }
}
