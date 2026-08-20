package io.akka.intentkit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 31-36 — which agents a cluster's lead may reach.
 *
 * <p>The eight targets and their outcomes are the eight probe 04 put through intentkit's own
 * tool against real rows (question-log #33, #34). The refusal messages are intentkit's, word
 * for word, because a caller reads them.
 */
public class DelegationTest {

  private static final Instant ARCHIVED = Instant.parse("2026-01-01T00:00:00Z");

  private final Map<String, AgentSummary> world = Map.of(
      "own-active", agent("own-active", "team-a", Visibility.PRIVATE, null),
      "own-archived", agent("own-archived", "team-a", Visibility.PRIVATE, ARCHIVED),
      "other-private", agent("other-private", "team-b", Visibility.PRIVATE, null),
      "other-public-followed", agent("other-public-followed", "team-b", Visibility.PUBLIC, null),
      "other-public-unfollowed",
          agent("other-public-unfollowed", "team-b", Visibility.PUBLIC, null),
      "other-public-archived",
          agent("other-public-archived", "team-b", Visibility.PUBLIC, ARCHIVED));

  private static AgentSummary agent(String id, String team, Visibility v, Instant archivedAt) {
    return new AgentSummary(id, team, id + "-slug", v, archivedAt);
  }

  private Delegation.Decision decide(String target, int callDepth) {
    var resolved = world.values().stream()
        .filter(a -> a.id().equals(target) || a.slug().equals(target))
        .findFirst()
        .orElse(null);
    return Delegation.decide("team-a", List.of("other-public-followed"), target, resolved,
        callDepth);
  }

  @Test
  public void ownClusterActiveAgentIsReachableByIdAndBySlug() {
    for (var target : List.of("own-active", "own-active-slug")) {
      var decision = decide(target, 0);
      assertThat(decision.allowed()).as(target).isTrue();
      assertThat(decision.agentId()).isEqualTo("own-active");
    }
  }

  @Test
  public void anArchivedAgentIsNeverReachable() {
    for (var target : List.of("own-archived", "other-public-archived")) {
      var decision = decide(target, 0);
      assertThat(decision.allowed()).as(target).isFalse();
      assertThat(decision.refusal()).isEqualTo("Agent '" + target + "' is archived");
    }
  }

  @Test
  public void anotherClustersAgentIsReachableOnlyWhenPublicAndFollowed() {
    assertThat(decide("other-public-followed", 0).allowed()).isTrue();

    for (var target : List.of("other-private", "other-public-unfollowed")) {
      var decision = decide(target, 0);
      assertThat(decision.allowed()).as(target).isFalse();
      assertThat(decision.refusal())
          .isEqualTo("Agent '" + target + "' is not accessible to this team. "
              + "Use lead_follow_agent to follow it first.");
    }
  }

  @Test
  public void anUnknownAgentIsRefusedAsNotFound() {
    var decision = decide("no-such-agent", 0);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.refusal()).isEqualTo("Agent 'no-such-agent' not found");
  }

  @Test
  public void delegationStopsAtDepthFive() {
    for (int depth : new int[] {0, 4}) {
      assertThat(decide("own-active", depth).allowed()).as("depth %d", depth).isTrue();
    }
    for (int depth : new int[] {5, 6}) {
      var decision = decide("own-active", depth);
      assertThat(decision.allowed()).as("depth %d", depth).isFalse();
      assertThat(decision.refusal())
          .isEqualTo("Maximum call_agent recursion depth (5) exceeded. "
              + "Cannot call another agent from this depth.");
    }
  }

  /** The depth guard sits ahead of the roster check, so it fires even for an unknown agent. */
  @Test
  public void theDepthGuardIsCheckedBeforeTheRoster() {
    assertThat(decide("no-such-agent", 5).refusal())
        .isEqualTo("Maximum call_agent recursion depth (5) exceeded. "
            + "Cannot call another agent from this depth.");
  }

  /** Following an agent that is private does not make it reachable. */
  @Test
  public void followingDoesNotOverridePrivacy() {
    var decision = Delegation.decide("team-a", List.of("other-private"), "other-private",
        world.get("other-private"), 0);
    assertThat(decision.allowed()).isFalse();
  }
}
