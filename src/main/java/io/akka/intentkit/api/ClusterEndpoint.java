package io.akka.intentkit.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Patch;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import io.akka.intentkit.application.AgentEntity;
import io.akka.intentkit.application.ClusterEntity;
import io.akka.intentkit.application.TaskEntity;
import io.akka.intentkit.application.TaskScheduler;
import io.akka.intentkit.domain.AgentSummary;
import io.akka.intentkit.domain.CronPolicy;
import io.akka.intentkit.domain.Execution;
import io.akka.intentkit.domain.ScheduleRejected;
import io.akka.intentkit.domain.ScriptedTurn;
import io.akka.intentkit.domain.TaskState;
import io.akka.intentkit.domain.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** The whole surface: a cluster, its agents, and its schedules. */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/clusters")
public class ClusterEndpoint {

  /** SPEC-001 rule 7. The two together bound a timer payload well under its ceiling. */
  private static final Pattern TASK_ID = Pattern.compile("^[a-z0-9-]{1,20}$");

  private static final Pattern TEAM_ID = Pattern.compile("^[a-z0-9-]{1,64}$");

  /** intentkit's own caps. They also keep a task's state far inside its replication limit. */
  private static final int NAME_MAX = 50;

  private static final int DESCRIPTION_MAX = 200;

  private static final int PROMPT_MAX = 20000;

  private final ComponentClient componentClient;
  private final TaskScheduler scheduler;

  public ClusterEndpoint(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.scheduler = new TaskScheduler(timers, componentClient);
  }

  public record CreateCluster(String ownerUserId) {}

  public record CreateAgent(
      String slug, String name, Visibility visibility, List<ScriptedTurn> script) {}

  public record CreateTask(
      String taskId,
      String name,
      String description,
      String cron,
      String prompt,
      Boolean enabled,
      String targetAgentId) {}

  public record UpdateTask(
      String name,
      String description,
      String cron,
      String prompt,
      Boolean enabled,
      String targetAgentId) {}

  public record TaskView(
      String teamId,
      String taskId,
      String name,
      String description,
      String cron,
      String prompt,
      boolean enabled,
      String targetAgentId,
      String status,
      Instant nextRunTime,
      long minGapMinutes,
      Execution running) {}

  // --- the cluster ------------------------------------------------------------------

  @Put("/{teamId}")
  public HttpResponse createCluster(String teamId, CreateCluster body) {
    requireTeamId(teamId);
    componentClient
        .forKeyValueEntity(teamId)
        .method(ClusterEntity::create)
        .invoke(body.ownerUserId());
    // The lead is an ordinary agent with a reserved id, so a lead-orchestrated run has a
    // thread and an activity log like any other run (question-log #32).
    var leadId = "team-" + teamId;
    componentClient
        .forKeyValueEntity(leadId)
        .method(AgentEntity::create)
        .invoke(new AgentEntity.Create(teamId, "lead", "Lead", Visibility.PRIVATE, List.of()));
    return HttpResponses.created();
  }

  public record ClusterView(
      String teamId,
      String ownerUserId,
      List<String> agentIds,
      List<String> followedAgentIds,
      List<String> leadDelegatesTo,
      List<String> taskIds) {}

  @Get("/{teamId}")
  public ClusterView getCluster(String teamId) {
    var state = cluster(teamId);
    return new ClusterView(state.teamId(), state.ownerUserId(), state.agentIds(),
        state.followedAgentIds(), state.leadDelegatesTo(), state.taskIds());
  }

  @Delete("/{teamId}/owner")
  public HttpResponse removeOwner(String teamId) {
    componentClient.forKeyValueEntity(teamId).method(ClusterEntity::removeOwner).invoke();
    return HttpResponses.ok();
  }

  @Put("/{teamId}/lead/delegates-to")
  public HttpResponse setLeadDelegatesTo(String teamId, List<String> targets) {
    componentClient
        .forKeyValueEntity(teamId)
        .method(ClusterEntity::setLeadDelegatesTo)
        .invoke(targets);
    return HttpResponses.ok();
  }

  // --- agents -----------------------------------------------------------------------

  @Put("/{teamId}/agents/{agentId}")
  public HttpResponse putAgent(String teamId, String agentId, CreateAgent body) {
    componentClient
        .forKeyValueEntity(agentId)
        .method(AgentEntity::create)
        .invoke(new AgentEntity.Create(teamId, body.slug(), body.name(), body.visibility(),
            body.script()));
    componentClient
        .forKeyValueEntity(teamId)
        .method(ClusterEntity::addAgent)
        .invoke(new ClusterEntity.AgentRef(agentId, body.slug()));
    return HttpResponses.created();
  }

  /** Archiving leaves the agent in place and unreachable, which is what intentkit does. */
  @Delete("/{teamId}/agents/{agentId}")
  public HttpResponse archiveAgent(String teamId, String agentId) {
    componentClient.forKeyValueEntity(agentId).method(AgentEntity::archive).invoke();
    return HttpResponses.ok();
  }

  @Get("/{teamId}/agents/{agentId}/threads/{chatId}")
  public List<io.akka.intentkit.domain.ReplyMessage> thread(
      String teamId, String agentId, String chatId) {
    return componentClient
        .forKeyValueEntity(agentId)
        .method(AgentEntity::thread)
        .invoke(chatId);
  }

  @Get("/{teamId}/agents/{agentId}/activities")
  public List<String> activities(String teamId, String agentId) {
    return componentClient.forKeyValueEntity(agentId).method(AgentEntity::activities).invoke();
  }

  @Post("/{teamId}/follows/{agentId}")
  public HttpResponse follow(String teamId, String agentId) {
    componentClient.forKeyValueEntity(teamId).method(ClusterEntity::follow).invoke(agentId);
    return HttpResponses.ok();
  }

  @Delete("/{teamId}/follows/{agentId}")
  public HttpResponse unfollow(String teamId, String agentId) {
    componentClient.forKeyValueEntity(teamId).method(ClusterEntity::unfollow).invoke(agentId);
    return HttpResponses.ok();
  }

  // --- schedules --------------------------------------------------------------------

  @Post("/{teamId}/tasks")
  public HttpResponse createTask(String teamId, CreateTask body) {
    requireTeamId(teamId);
    if (body.taskId() == null || !TASK_ID.matcher(body.taskId()).matches()) {
      throw HttpException.badRequest(
          "id must contain only lowercase letters, numbers, and dashes, and be at most 20 bytes");
    }
    var schedule = validate(body.cron());
    requireLengths(body.name(), body.description(), body.prompt());
    boolean enabled = body.enabled() == null || body.enabled();
    requireTargetOnRoster(teamId, body.targetAgentId());

    var jobId = teamId + "-" + body.taskId();
    if (componentClient.forEventSourcedEntity(jobId).method(TaskEntity::get).invoke()
        .exists()) {
      // Asked before anything is armed: arming first would move the *existing* task's next
      // fire to now, and the caller would be told the create failed.
      throw HttpException.badRequest("Task " + body.taskId() + " already exists");
    }
    // The timer is armed before the task is stored, so a task can never exist unscheduled
    // (rule 13). A fire for a task that is not there deletes its own timer.
    var nextRunTime = enabled ? scheduler.arm(jobId, schedule, Instant.now()).orElse(null) : null;
    componentClient
        .forEventSourcedEntity(jobId)
        .method(TaskEntity::create)
        .invoke(new TaskEntity.Create(teamId, body.taskId(), body.name(), body.description(),
            body.cron(), body.prompt(), enabled, body.targetAgentId(), nextRunTime));
    componentClient
        .forKeyValueEntity(teamId)
        .method(ClusterEntity::addTask)
        .invoke(body.taskId());
    return HttpResponses.created();
  }

  @Patch("/{teamId}/tasks/{taskId}")
  public TaskView updateTask(String teamId, String taskId, UpdateTask body) {
    var jobId = teamId + "-" + taskId;
    var current = task(jobId);
    // A field the caller did not name keeps the value it had: intentkit merges an update
    // over the stored task rather than replacing it, so a rename does not erase a prompt.
    var cron = body.cron() == null ? current.cron() : body.cron();
    var prompt = body.prompt() == null ? current.prompt() : body.prompt();
    var name = body.name() == null ? current.name() : body.name();
    var description =
        body.description() == null ? current.description() : body.description();
    boolean enabled = body.enabled() == null ? current.enabled() : body.enabled();
    var target = body.targetAgentId() == null ? current.targetAgentId() : body.targetAgentId();
    var schedule = validate(cron);
    requireLengths(name, description, prompt);
    if (body.targetAgentId() != null) {
      requireTargetOnRoster(teamId, body.targetAgentId());
    }

    var merged = new TaskState(teamId, taskId, name, description, cron, prompt,
        enabled, target, current.status(), current.nextRunTime(), current.running(),
        current.history(), true);

    // Only a change to one of the four scheduling-relevant fields touches the timer
    // (rule 16); a rename leaves the pending fire exactly where it was.
    var nextRunTime = current.nextRunTime();
    if (!merged.schedulingSignature().equals(current.schedulingSignature())) {
      if (enabled) {
        nextRunTime = scheduler.arm(jobId, schedule, Instant.now()).orElse(null);
      } else {
        scheduler.cancel(jobId);
        nextRunTime = null;
      }
    }

    componentClient
        .forEventSourcedEntity(jobId)
        .method(TaskEntity::update)
        .invoke(new TaskEntity.Update(name, description, cron, prompt, enabled,
            target, nextRunTime));
    return view(task(jobId));
  }

  @Get("/{teamId}/tasks/{taskId}")
  public TaskView getTask(String teamId, String taskId) {
    return view(task(teamId + "-" + taskId));
  }

  @Delete("/{teamId}/tasks/{taskId}")
  public HttpResponse deleteTask(String teamId, String taskId) {
    var jobId = teamId + "-" + taskId;
    scheduler.cancel(jobId);
    componentClient.forEventSourcedEntity(jobId).method(TaskEntity::delete).invoke();
    componentClient.forKeyValueEntity(teamId).method(ClusterEntity::removeTask).invoke(taskId);
    return HttpResponses.noContent();
  }

  /**
   * Run a task now.
   *
   * <p>Accepted for a disabled task, and refused while a run is already in progress — the
   * same two answers intentkit gives. The run is armed as a zero-delay timer rather than
   * started in this request, so it survives a restart. SPEC-001 decision 8.
   */
  @Post("/{teamId}/tasks/{taskId}/execute")
  public HttpResponse executeTask(String teamId, String taskId, ExecuteBody body) {
    var jobId = teamId + "-" + taskId;
    var current = task(jobId);
    if (current.running() != null
        && Instant.now().isBefore(current.running().startedAt().plus(TaskState.STALE_AFTER))) {
      throw HttpException.error(
          StatusCodes.CONFLICT, "Task is currently running, try again later.");
    }
    var userId = body == null || body.userId() == null ? "manual" : body.userId();
    scheduler.armManual(jobId, userId);
    return HttpResponses.accepted();
  }

  public record ExecuteBody(String userId) {}

  @Get("/{teamId}/tasks/{taskId}/executions")
  public List<Execution> executions(String teamId, String taskId) {
    return componentClient
        .forEventSourcedEntity(teamId + "-" + taskId)
        .method(TaskEntity::executions)
        .invoke();
  }

  // --- helpers ----------------------------------------------------------------------

  private io.akka.intentkit.domain.ClusterState cluster(String teamId) {
    return componentClient.forKeyValueEntity(teamId).method(ClusterEntity::get).invoke();
  }

  private TaskState task(String jobId) {
    var state = componentClient.forEventSourcedEntity(jobId).method(TaskEntity::get).invoke();
    if (!state.exists()) {
      throw HttpException.notFound();
    }
    return state;
  }

  private io.akka.intentkit.domain.CronSchedule validate(String cron) {
    try {
      return CronPolicy.validate(cron);
    } catch (ScheduleRejected e) {
      throw HttpException.badRequest(e.key() + ": " + e.getMessage());
    }
  }

  /**
   * A task may only target an agent of its own cluster that is not archived.
   *
   * <p>Checked when the task is written, which is where intentkit checks it, so a schedule
   * pointing at an agent nobody can reach never gets stored.
   */
  private void requireTargetOnRoster(String teamId, String targetAgentId) {
    if (targetAgentId == null) {
      return;
    }
    AgentSummary summary;
    try {
      summary =
          componentClient.forKeyValueEntity(targetAgentId).method(AgentEntity::summary).invoke();
    } catch (RuntimeException e) {
      // An agent that does not exist and an agent that belongs elsewhere are the same
      // answer to the caller, and it is intentkit's answer rather than the entity's.
      summary = null;
    }
    if (summary == null || !teamId.equals(summary.teamId()) || summary.archived()) {
      throw HttpException.badRequest(
          "Target agent " + targetAgentId + " is not an active agent of this team.");
    }
  }

  private static void requireLengths(String name, String description, String prompt) {
    if (name != null && name.length() > NAME_MAX) {
      throw HttpException.badRequest("name must be at most " + NAME_MAX + " characters");
    }
    if (description != null && description.length() > DESCRIPTION_MAX) {
      throw HttpException.badRequest(
          "description must be at most " + DESCRIPTION_MAX + " characters");
    }
    if (prompt == null || prompt.length() > PROMPT_MAX) {
      throw HttpException.badRequest(
          "prompt is required and must be at most " + PROMPT_MAX + " characters");
    }
  }

  private static void requireTeamId(String teamId) {
    if (!TEAM_ID.matcher(teamId).matches()) {
      throw HttpException.badRequest(
          "cluster id must contain only lowercase letters, numbers and dashes, "
              + "and be at most 64 bytes");
    }
  }

  private TaskView view(TaskState state) {
    return new TaskView(
        state.teamId(),
        state.taskId(),
        state.name(),
        state.description(),
        state.cron(),
        state.prompt(),
        state.enabled(),
        state.targetAgentId(),
        state.status() == null ? null : state.status().wireName(),
        state.nextRunTime(),
        CronPolicy.validate(state.cron()).minGapMinutes(Instant.now()),
        state.running());
  }
}
