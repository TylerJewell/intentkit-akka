package io.akka.intentkit.application;

import akka.javasdk.client.ComponentClient;
import io.akka.intentkit.domain.AuthorType;
import io.akka.intentkit.domain.ClusterState;
import io.akka.intentkit.domain.CronPolicy;
import io.akka.intentkit.domain.CronSchedule;
import io.akka.intentkit.domain.Delegation;
import io.akka.intentkit.domain.Execution;
import io.akka.intentkit.domain.ReplyMessage;
import io.akka.intentkit.domain.RunOutcome;
import io.akka.intentkit.domain.ScheduleRejected;
import io.akka.intentkit.domain.TaskState;
import io.akka.intentkit.domain.Trigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One scheduled run, start to finish — SPEC-001 rules 13-15, 19-27, 30.
 *
 * <p>The three conditions intentkit's sweep prunes on — the task is gone, the task is
 * disabled, the cluster has no owner — are checked here, when the timer fires, rather than
 * once a minute over every task. SPEC-001 decision 2.
 *
 * <p>Nothing in this class throws on a failed run. A scheduled call that fails is
 * re-delivered by the runtime (question-log T4), and a run that failed because the agent
 * failed is a recorded outcome, not a call to repeat.
 */
public final class TaskRunner {

  private static final Logger logger = LoggerFactory.getLogger(TaskRunner.class);

  /** One chat per task, so the wipe before each run is per task (question-log #26). */
  static final String CHAT_PREFIX = "autonomous-";

  private final ComponentClient componentClient;
  private final TaskScheduler scheduler;

  public TaskRunner(ComponentClient componentClient, TaskScheduler scheduler) {
    this.componentClient = componentClient;
    this.scheduler = scheduler;
  }

  /** A timer fired for {@code jobId}. */
  public void fireScheduled(String jobId) {
    var task = componentClient.forEventSourcedEntity(jobId).method(TaskEntity::get).invoke();

    if (!task.exists()) {
      scheduler.cancel(jobId);
      return;
    }
    if (!task.enabled()) {
      scheduler.cancel(jobId);
      return;
    }
    var cluster = cluster(task.teamId());

    CronSchedule schedule;
    try {
      schedule = CronPolicy.validate(task.cron());
    } catch (ScheduleRejected e) {
      // Nothing can be armed from a schedule that will not parse, and throwing would ask
      // the runtime to deliver this fire again for as long as the task exists.
      logger.error("Task {} has an unusable schedule {}: {}", task.taskId(), task.cron(),
          e.getMessage());
      scheduler.cancel(jobId);
      return;
    }

    var scheduledFor = task.nextRunTime() == null ? Instant.now() : task.nextRunTime();
    // Re-armed from the scheduled time and before the run, so a run longer than the interval
    // does not drag the schedule along behind it (rule 10).
    var next = scheduler.arm(jobId, schedule, scheduledFor);
    componentClient
        .forEventSourcedEntity(jobId)
        .method(TaskEntity::setNextRunTime)
        .invoke(new TaskEntity.NextRun(next.orElse(null)));

    if (cluster.ownerUserId() == null) {
      // Skipped, not stopped. intentkit's sweep leaves an ownerless team's task out of the
      // plan and schedules it again on the first sweep after an owner exists, so a task
      // whose cluster loses its owner resumes rather than needing to be re-enabled.
      logger.warn("Cluster {} has no owner; skipping task {}", task.teamId(), task.taskId());
      return;
    }

    run(jobId, task, cluster, Trigger.CRON, cluster.ownerUserId(), null, scheduledFor);
  }

  /** A manual run, requested by {@code userId}. */
  public void fireManual(String jobId, String userId) {
    var task = componentClient.forEventSourcedEntity(jobId).method(TaskEntity::get).invoke();
    if (!task.exists()) {
      return;
    }
    var cluster = cluster(task.teamId());
    var now = Instant.now();
    run(jobId, task, cluster, Trigger.MANUAL, userId, userId, now);
  }

  private ClusterState cluster(String teamId) {
    return componentClient.forKeyValueEntity(teamId).method(ClusterEntity::get).invoke();
  }

  private void run(
      String jobId,
      TaskState task,
      ClusterState cluster,
      Trigger trigger,
      String attributedTo,
      String triggeredBy,
      Instant scheduledFor) {

    var effectiveAgentId =
        task.targetAgentId() == null ? cluster.leadAgentId() : task.targetAgentId();
    var chatId = CHAT_PREFIX + task.taskId();
    var startedAt = Instant.now();
    var execution =
        Execution.starting(
            UUID.randomUUID().toString(),
            task.taskId(),
            effectiveAgentId,
            task.targetAgentId(),
            chatId,
            attributedTo,
            trigger,
            triggeredBy,
            scheduledFor,
            startedAt);

    var claim =
        componentClient
            .forEventSourcedEntity(jobId)
            .method(TaskEntity::claim)
            .invoke(new TaskEntity.ClaimRequest(execution, startedAt));
    if (!claim.granted()) {
      logger.warn("Task {} skipped: {}", task.taskId(), claim.refusal());
      return;
    }

    // Nothing carries over between runs: the thread is wiped before the agent is asked
    // anything (rule 23). A failure here does not stop the run, which is what intentkit does.
    try {
      componentClient
          .forKeyValueEntity(effectiveAgentId)
          .method(AgentEntity::clearThread)
          .invoke(chatId);
    } catch (RuntimeException e) {
      logger.warn("Failed to clear thread for task {}: {}", task.taskId(), e.toString());
    }

    RunOutcome outcome;
    try {
      var messages =
          task.targetAgentId() == null
              ? viaLead(cluster, chatId, task.prompt())
              : direct(task.targetAgentId(), chatId, task.prompt());
      outcome = messages.failure() == null
          ? RunOutcome.of(messages.replies())
          : RunOutcome.ofFailure(messages.failure());
    } catch (RuntimeException e) {
      outcome = RunOutcome.ofFailure(e);
    }

    componentClient
        .forEventSourcedEntity(jobId)
        .method(TaskEntity::finish)
        .invoke(new TaskEntity.FinishRequest(execution.id(), outcome.status(), outcome.error(),
            outcome.stats(), Instant.now()));

    if (outcome.error() != null) {
      recordActivity(effectiveAgentId, outcome.error());
    }
  }

  private record Produced(List<ReplyMessage> replies, String failure) {}

  private Produced direct(String agentId, String chatId, String prompt) {
    var turn =
        componentClient
            .forKeyValueEntity(agentId)
            .method(AgentEntity::runTurn)
            .invoke(new AgentEntity.TurnRequest(chatId, prompt));
    // The turn's own duration, waited out here rather than inside the entity, so a slow
    // agent holds up its own run and nothing else.
    if (turn.delayMillis() > 0) {
      try {
        Thread.sleep(turn.delayMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return new Produced(turn.replies(), turn.failedWith());
  }

  /**
   * A run with no target agent goes through the cluster's lead, which delegates.
   *
   * <p>Every delegation is authorised against the roster and the follow list before the
   * agent is reached, and a refusal becomes a system message rather than reaching anyone
   * (rules 33, 35). Each delegation opens its own chat and counts one level of depth
   * (rule 37).
   */
  private Produced viaLead(ClusterState cluster, String chatId, String prompt) {
    if (cluster.leadDelegatesTo().isEmpty()) {
      return direct(cluster.leadAgentId(), chatId, prompt);
    }
    var replies = new ArrayList<ReplyMessage>();
    for (var target : cluster.leadDelegatesTo()) {
      // A slug only means something inside this cluster; an id names an agent anywhere.
      // Falling back to the id is what lets a refusal say whether the agent exists at all,
      // which is the difference between "not accessible" and "not found".
      var resolvedId = cluster.resolve(target).orElse(target);
      var summary = summaryOf(resolvedId);
      var decision =
          Delegation.decide(
              cluster.teamId(), cluster.followedAgentIds(), target, summary, /* callDepth */ 0);
      if (!decision.allowed()) {
        replies.add(ReplyMessage.of(AuthorType.SYSTEM, decision.refusal()));
        continue;
      }
      var turn = direct(decision.agentId(), "call-" + UUID.randomUUID(), prompt);
      if (turn.failure() != null) {
        return new Produced(replies, turn.failure());
      }
      replies.addAll(turn.replies());
    }
    return new Produced(replies, null);
  }

  private io.akka.intentkit.domain.AgentSummary summaryOf(String agentId) {
    try {
      return componentClient
          .forKeyValueEntity(agentId)
          .method(AgentEntity::summary)
          .invoke();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void recordActivity(String agentId, String text) {
    try {
      componentClient
          .forKeyValueEntity(agentId)
          .method(AgentEntity::recordActivity)
          .invoke(text);
    } catch (RuntimeException e) {
      logger.warn("Failed to create error activity for {}: {}", agentId, e.toString());
    }
  }
}
