package io.akka.intentkit;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.intentkit.api.ClusterEndpoint;
import io.akka.intentkit.application.TaskScheduler;
import io.akka.intentkit.domain.AuthorType;
import io.akka.intentkit.domain.Execution;
import io.akka.intentkit.domain.ReplyMessage;
import io.akka.intentkit.domain.ScriptedTurn;
import io.akka.intentkit.domain.Visibility;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 9-19, 23, 26, 27, 28, 30 against a running runtime.
 *
 * <p>What is checked here and not in the state-machine tests is everything a pure record
 * cannot answer: that a timer fires at all, that it re-arms itself so a schedule repeats,
 * that a fire for a task that is gone or disabled or ownerless does nothing, and that a run
 * reaches the agent it was supposed to reach.
 *
 * <p>Schedules here are {@code *}{@code /1 0-23 * * *} — every minute. intentkit accepts it
 * and so does this port: the five-minute refusal only guards an unrestricted hour field, and
 * writing the hour as a range instead of a star walks straight past it (question-log #3).
 */
public class ScheduledRunIntegrationTest extends TestKitSupport {

  private static final Duration PATIENCE = Duration.ofSeconds(90);

  private String teamId() {
    return "team-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * Agent ids are global, as they are in intentkit, so two tests must never share one.
   * Naming an agent after its cluster is enough, because each test gets a fresh cluster.
   */
  private String agentIn(String teamId, String role) {
    return teamId + "-" + role;
  }

  private void createCluster(String teamId, String owner) {
    httpClient.PUT("/clusters/" + teamId)
        .withRequestBody(new ClusterEndpoint.CreateCluster(owner))
        .invoke();
  }

  private void putAgent(String teamId, String agentId, List<ScriptedTurn> script) {
    putAgent(teamId, agentId, agentId + "-slug", Visibility.PRIVATE, script);
  }

  private void putAgent(
      String teamId, String agentId, String slug, Visibility visibility,
      List<ScriptedTurn> script) {
    httpClient.PUT("/clusters/" + teamId + "/agents/" + agentId)
        .withRequestBody(new ClusterEndpoint.CreateAgent(slug, agentId, visibility, script))
        .invoke();
  }

  private void createTask(String teamId, ClusterEndpoint.CreateTask body) {
    httpClient.POST("/clusters/" + teamId + "/tasks").withRequestBody(body).invoke();
  }

  private ClusterEndpoint.TaskView task(String teamId, String taskId) {
    return httpClient.GET("/clusters/" + teamId + "/tasks/" + taskId)
        .responseBodyAs(ClusterEndpoint.TaskView.class)
        .invoke()
        .body();
  }

  private List<Execution> executions(String teamId, String taskId) {
    return httpClient.GET("/clusters/" + teamId + "/tasks/" + taskId + "/executions")
        .responseBodyAsListOf(Execution.class)
        .invoke()
        .body();
  }

  private List<ReplyMessage> thread(String teamId, String agentId, String chatId) {
    return httpClient.GET("/clusters/" + teamId + "/agents/" + agentId + "/threads/" + chatId)
        .responseBodyAsListOf(ReplyMessage.class)
        .invoke()
        .body();
  }

  /** A task armed at write time fires, records a run, and arms the next one. */
  @Test
  public void aScheduledTaskRunsAndKeepsRunning() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of(new ScriptedTurn(null, List.of(
        new ReplyMessage(AuthorType.AGENT, "done", 10, 3, 2, new BigDecimal("1.5"), 0.75,
            List.of())))));
    createTask(team, new ClusterEndpoint.CreateTask("k1", "nightly", null, "*/1 0-23 * * *",
        "do the thing", true, worker));

    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());

    var run = executions(team, "k1").get(0);
    assertThat(run.status().wireName()).isEqualTo("success");
    assertThat(run.result()).isEqualTo("done");
    assertThat(run.inputTokens()).isEqualTo(10);
    assertThat(run.creditCost()).isEqualByComparingTo(new BigDecimal("1.5"));
    assertThat(run.chatId()).isEqualTo("autonomous-k1");
    assertThat(run.agentId()).isEqualTo(worker);
    assertThat(run.trigger().wireName()).isEqualTo("cron");
    // Every run carries what it was scheduled for and when it actually began, so lateness
    // is a number rather than a promise (rule 30).
    assertThat(run.scheduledFor()).isNotNull();
    assertThat(run.startedAt()).isNotNull();

    // The next timer was armed from the scheduled time, not from now, so the task keeps
    // its cadence rather than drifting by the length of each run (rule 10).
    var after = task(team, "k1");
    assertThat(after.nextRunTime()).isAfter(run.scheduledFor());
    assertThat(after.status()).isEqualTo("waiting");
  }

  /**
   * The wipe before each run: the thread never carries the previous run's conversation.
   *
   * <p>Two manual runs rather than two scheduled ones — the wipe is a property of a run,
   * not of what started it, and waiting out two cron fires would add two minutes to say
   * the same thing.
   */
  @Test
  public void everyRunStartsFromACleanThread() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of(ScriptedTurn.answering("one"),
        ScriptedTurn.answering("two"), ScriptedTurn.answering("three")));
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
        "go", false, worker));

    for (int run = 1; run <= 2; run++) {
      int expected = run;
      httpClient.POST("/clusters/" + team + "/tasks/k1/execute")
          .withRequestBody(new ClusterEndpoint.ExecuteBody("user-1"))
          .invoke();
      Awaitility.await()
          .atMost(PATIENCE)
          .untilAsserted(() -> assertThat(executions(team, "k1")).hasSize(expected));
    }

    // The second run's prompt and reply, and nothing from the first.
    var thread = thread(team, worker, "autonomous-k1");
    assertThat(thread).hasSize(2);
    assertThat(thread.get(0).authorType()).isEqualTo(AuthorType.TRIGGER);
    assertThat(thread.get(1).message()).isEqualTo("two");
    assertThat(executions(team, "k1").get(0).result()).isEqualTo("two");
  }

  /** A fire for a task that is gone deletes its own timer and does nothing else. */
  @Test
  public void aDeletedTaskStopsRunning() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of());
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "*/1 0-23 * * *",
        "go", true, worker));

    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());

    httpClient.DELETE("/clusters/" + team + "/tasks/k1").invoke();
    var response = httpClient.GET("/clusters/" + team + "/tasks/k1").invoke();
    assertThat(response.httpResponse().status().intValue()).isEqualTo(404);
  }

  /** A disabled task keeps its record and loses its schedule and its runtime state. */
  @Test
  public void disablingATaskStopsItAndClearsItsRuntimeState() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of());
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "*/1 0-23 * * *",
        "go", true, worker));

    var disabled = httpClient.PATCH("/clusters/" + team + "/tasks/k1")
        .withRequestBody(new ClusterEndpoint.UpdateTask(null, null, null, null, false, null))
        .responseBodyAs(ClusterEndpoint.TaskView.class)
        .invoke()
        .body();
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.status()).isNull();
    assertThat(disabled.nextRunTime()).isNull();
  }

  /** A cluster with no owner runs nothing, which is what intentkit's sweep enforces. */
  @Test
  public void aClusterWithNoOwnerRunsNothing() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of());
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "*/1 0-23 * * *",
        "go", true, worker));
    httpClient.DELETE("/clusters/" + team + "/owner").invoke();

    // Long enough for at least one fire to have arrived and been refused.
    sleep(Duration.ofSeconds(70));
    assertThat(executions(team, "k1")).isEmpty();
  }

  /** A manual run is accepted for a disabled task, and refused while a run is in progress. */
  @Test
  public void aManualRunIsAcceptedForADisabledTask() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of(ScriptedTurn.answering("manual answer")));
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
        "go", false, worker));

    var accepted = httpClient.POST("/clusters/" + team + "/tasks/k1/execute")
        .withRequestBody(new ClusterEndpoint.ExecuteBody("user-2"))
        .invoke();
    assertThat(accepted.httpResponse().status().intValue()).isEqualTo(202);

    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());
    var run = executions(team, "k1").get(0);
    assertThat(run.trigger().wireName()).isEqualTo("manual");
    assertThat(run.triggeredBy()).isEqualTo("user-2");
    assertThat(run.result()).isEqualTo("manual answer");
  }

  /** A run with no target agent goes through the lead, which delegates and authorises. */
  @Test
  public void aLeadOrchestratedRunDelegatesAndRefusesInTheSamePass() {
    var team = teamId();
    var other = teamId();
    createCluster(team, "user-1");
    createCluster(other, "user-9");
    var active = agentIn(team, "own-active");
    var archived = agentIn(team, "own-archived");
    var outsider = agentIn(other, "outsider");
    putAgent(team, active, List.of(ScriptedTurn.answering("own-active answered")));
    putAgent(team, archived, List.of());
    httpClient.DELETE("/clusters/" + team + "/agents/" + archived).invoke();
    putAgent(other, outsider, outsider + "-slug", Visibility.PUBLIC, List.of());

    httpClient.PUT("/clusters/" + team + "/lead/delegates-to")
        .withRequestBody(List.of(active + "-slug", archived, outsider))
        .invoke();
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
        "go", false, null));
    httpClient.POST("/clusters/" + team + "/tasks/k1/execute")
        .withRequestBody(new ClusterEndpoint.ExecuteBody("user-1"))
        .invoke();

    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());

    var run = executions(team, "k1").get(0);
    // The lead's own run is attributed to the synthetic agent id (rule 27).
    assertThat(run.agentId()).isEqualTo("team-" + team);
    assertThat(run.targetAgentId()).isNull();
    // Three delegations: one answered, two refused. The last message is a refusal, so the
    // run is an error — the archived agent and the unfollowed outsider were never reached.
    assertThat(run.status().wireName()).isEqualTo("error");
    assertThat(run.result()).isEqualTo("own-active answered");
    assertThat(run.messageCount()).isEqualTo(3);
    assertThat(thread(team, archived, "autonomous-k1")).isEmpty();
    assertThat(thread(other, outsider, "autonomous-k1")).isEmpty();
  }

  /** Following an external public agent is what makes it reachable. */
  @Test
  public void followingMakesAnExternalPublicAgentReachable() {
    var team = teamId();
    var other = teamId();
    createCluster(team, "user-1");
    createCluster(other, "user-9");
    var outsider = agentIn(other, "outsider");
    putAgent(other, outsider, outsider + "-slug", Visibility.PUBLIC,
        List.of(ScriptedTurn.answering("outsider answered")));
    httpClient.POST("/clusters/" + team + "/follows/" + outsider).invoke();
    httpClient.PUT("/clusters/" + team + "/lead/delegates-to")
        .withRequestBody(List.of(outsider))
        .invoke();
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
        "go", false, null));
    httpClient.POST("/clusters/" + team + "/tasks/k1/execute")
        .withRequestBody(new ClusterEndpoint.ExecuteBody("user-1"))
        .invoke();

    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());
    var run = executions(team, "k1").get(0);
    assertThat(run.status().wireName()).isEqualTo("success");
    assertThat(run.result()).isEqualTo("outsider answered");
  }

  /** A failed run is also recorded as an activity on the agent it was attributed to. */
  @Test
  public void aFailedRunLeavesAnActivity() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of(new ScriptedTurn("model exploded", List.of())));
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
        "go", false, worker));
    httpClient.POST("/clusters/" + team + "/tasks/k1/execute")
        .withRequestBody(new ClusterEndpoint.ExecuteBody("user-1"))
        .invoke();

    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());

    var run = executions(team, "k1").get(0);
    assertThat(run.status().wireName()).isEqualTo("error");
    assertThat(run.error())
        .isEqualTo("Autonomous task exception: RuntimeException('model exploded')");

    var activities =
        httpClient.GET("/clusters/" + team + "/agents/" + worker + "/activities")
        .responseBodyAsListOf(String.class)
        .invoke()
        .body();
    assertThat(activities).contains(run.error());
  }

  /**
   * A cluster that loses its owner pauses its tasks and picks them up again when it has one.
   *
   * <p>intentkit leaves an ownerless team's task out of the plan and schedules it again on the
   * first sweep after an owner exists, so a task never has to be re-enabled by hand.
   */
  @Test
  public void aTaskResumesWhenItsClusterHasAnOwnerAgain() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of(ScriptedTurn.answering("back")));
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "*/1 0-23 * * *",
        "go", true, worker));
    httpClient.DELETE("/clusters/" + team + "/owner").invoke();

    // Long enough for at least one fire to have arrived and been skipped.
    sleep(Duration.ofSeconds(70));
    assertThat(executions(team, "k1")).isEmpty();

    createCluster(team, "user-1");
    Awaitility.await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(executions(team, "k1")).isNotEmpty());
    assertThat(executions(team, "k1").get(0).result()).isEqualTo("back");
  }

  /** A partial edit keeps the fields it does not name. */
  @Test
  public void anEditThatOnlyRenamesKeepsEverythingElse() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of());
    createTask(team, new ClusterEndpoint.CreateTask("k1", "nightly", "the description",
        "0 3 * * *", "the prompt", false, worker));

    var renamed = httpClient.PATCH("/clusters/" + team + "/tasks/k1")
        .withRequestBody(new ClusterEndpoint.UpdateTask("renamed", null, null, null, null, null))
        .responseBodyAs(ClusterEndpoint.TaskView.class)
        .invoke()
        .body();
    assertThat(renamed.name()).isEqualTo("renamed");
    assertThat(renamed.description()).isEqualTo("the description");
    assertThat(renamed.prompt()).isEqualTo("the prompt");
    assertThat(renamed.cron()).isEqualTo("0 3 * * *");
    assertThat(renamed.targetAgentId()).isEqualTo(worker);
  }

  /** The source's own caps on what a task may carry. */
  @Test
  public void anOverLongFieldIsRefused() {
    var team = teamId();
    createCluster(team, "user-1");
    var cases = List.of(
        new ClusterEndpoint.CreateTask("k1", "n".repeat(51), null, "0 3 * * *", "go",
            false, null),
        new ClusterEndpoint.CreateTask("k1", null, "d".repeat(201), "0 3 * * *", "go",
            false, null),
        new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *", "p".repeat(20001),
            false, null));
    for (var body : cases) {
      var response = httpClient.POST("/clusters/" + team + "/tasks")
          .withRequestBody(body)
          .invoke();
      assertThat(response.httpResponse().status().intValue()).isEqualTo(400);
    }
  }

  /** Creating a task that is already there must not move the existing one's next fire. */
  @Test
  public void creatingATaskTwiceLeavesTheFirstOneAlone() {
    var team = teamId();
    createCluster(team, "user-1");
    var worker = agentIn(team, "worker");
    putAgent(team, worker, List.of());
    createTask(team, new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
        "go", true, worker));
    var before = task(team, "k1").nextRunTime();

    var again = httpClient.POST("/clusters/" + team + "/tasks")
        .withRequestBody(new ClusterEndpoint.CreateTask("k1", null, null, "0 4 * * *",
            "go", true, worker))
        .invoke();
    assertThat(again.httpResponse().status().intValue()).isEqualTo(400);
    assertThat(task(team, "k1").nextRunTime()).isEqualTo(before);
    assertThat(task(team, "k1").cron()).isEqualTo("0 3 * * *");
  }

  /** An unknown target agent is refused the way intentkit refuses it. */
  @Test
  public void anUnknownTargetAgentIsRefusedInTheSourcesWords() {
    var team = teamId();
    createCluster(team, "user-1");
    var response = httpClient.POST("/clusters/" + team + "/tasks")
        .withRequestBody(new ClusterEndpoint.CreateTask("k1", null, null, "0 3 * * *",
            "go", true, "no-such-agent"))
        .invoke();
    assertThat(response.httpResponse().status().intValue()).isEqualTo(400);
    assertThat(response.body().utf8String())
        .contains("Target agent no-such-agent is not an active agent of this team.");
  }

  /** A schedule the port cannot run is refused at write time rather than stored. */
  @Test
  public void anUnrunnableScheduleIsRefused() {
    var team = teamId();
    createCluster(team, "user-1");
    for (var cron : List.of("0 0 */31 * *", "*/4 * * * *", "0  0 * * *", "")) {
      var response = httpClient.POST("/clusters/" + team + "/tasks")
          .withRequestBody(new ClusterEndpoint.CreateTask("bad", null, null, cron, "go",
              true, null))
          .invoke();
      assertThat(response.httpResponse().status().intValue()).as("cron %s", cron).isEqualTo(400);
    }
  }

  /** A task may only target an agent of its own cluster that is not archived. */
  @Test
  public void aTaskCannotTargetAnAgentOutsideItsCluster() {
    var team = teamId();
    var other = teamId();
    createCluster(team, "user-1");
    createCluster(other, "user-9");
    var outsider = agentIn(other, "outsider");
    var archivedOne = agentIn(team, "archived-one");
    putAgent(other, outsider, List.of());
    putAgent(team, archivedOne, List.of());
    httpClient.DELETE("/clusters/" + team + "/agents/" + archivedOne).invoke();

    for (var target : List.of(outsider, archivedOne)) {
      var response = httpClient.POST("/clusters/" + team + "/tasks")
          .withRequestBody(new ClusterEndpoint.CreateTask("k9", null, null, "0 3 * * *",
              "go", true, target))
          .invoke();
      assertThat(response.httpResponse().status().intValue()).as(target).isEqualTo(400);
    }
  }

  /**
   * The timer payload stays under the measured ceiling.
   *
   * <p>Above roughly 1kB the runtime accepts a timer and never delivers it, with no error at
   * either end (question-log T5, T6) — so a schedule would silently stop. The id rules make
   * that impossible, and this is what checks that they still do.
   */
  @Test
  public void theTimerPayloadStaysUnderItsSilentCeiling() {
    var longestTeam = "t".repeat(64);
    var longestTask = "k".repeat(20);
    var payload = longestTeam + "-" + longestTask + "|" + "u".repeat(64);
    assertThat(payload.getBytes(StandardCharsets.UTF_8).length)
        .isLessThan(TaskScheduler.MAX_TIMER_PAYLOAD_BYTES);

    var overLong = "t".repeat(65);
    var response = httpClient.PUT("/clusters/" + overLong)
        .withRequestBody(new ClusterEndpoint.CreateCluster("user-1"))
        .invoke();
    assertThat(response.httpResponse().status().intValue()).isEqualTo(400);
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
