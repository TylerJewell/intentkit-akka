package io.akka.intentkit.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;

/**
 * Where a schedule actually happens — SPEC-001 rules 10, 19.
 *
 * <p>One fire runs the task and arms the next one. A fire is delivered at least once
 * (question-log T4), so a redelivery must not produce a second run of the same occurrence;
 * the run slot in {@link TaskEntity} is what makes that true, and this class never throws
 * for a run that merely failed, because throwing is what asks for a redelivery.
 */
@Component(id = "task-timer")
public class TaskTimer extends TimedAction {

  private final TaskRunner runner;

  public TaskTimer(ComponentClient componentClient, TimerScheduler timers) {
    this.runner = new TaskRunner(componentClient, new TaskScheduler(timers, componentClient));
  }

  public Effect fire(String jobId) {
    runner.fireScheduled(jobId);
    return effects().done();
  }

  /** Payload is {@code jobId|userId}; a job id never contains a bar. */
  public Effect fireManual(String payload) {
    int split = payload.lastIndexOf('|');
    runner.fireManual(payload.substring(0, split), payload.substring(split + 1));
    return effects().done();
  }
}
