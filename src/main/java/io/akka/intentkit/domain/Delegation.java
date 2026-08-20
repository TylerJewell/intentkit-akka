package io.akka.intentkit.domain;

import java.util.List;

/**
 * Whether a cluster's lead may reach a given agent — SPEC-001 rules 31-36.
 *
 * <p>Own-cluster agents are always reachable; an agent of another cluster only when it is
 * public *and* this cluster follows it, which makes the follow list authoritative for what
 * the lead can call. An archived agent is unreachable either way.
 *
 * <p>The depth guard is checked first, before the agent is even resolved, because that is
 * where intentkit puts it — a call at depth 5 is refused for recursion whether or not the
 * target exists (question-log #36).
 */
public final class Delegation {

  public static final int MAX_CALL_DEPTH = 5;

  private Delegation() {}

  public static Decision decide(
      String callerTeamId,
      List<String> followedAgentIds,
      String requested,
      AgentSummary resolved,
      int callDepth) {

    if (callDepth >= MAX_CALL_DEPTH) {
      return Decision.refused(
          "Maximum call_agent recursion depth (" + MAX_CALL_DEPTH + ") exceeded. "
              + "Cannot call another agent from this depth.");
    }
    if (resolved == null) {
      return Decision.refused("Agent '" + requested + "' not found");
    }
    if (resolved.archived()) {
      return Decision.refused("Agent '" + requested + "' is archived");
    }
    if (!resolved.teamId().equals(callerTeamId)) {
      boolean reachable =
          resolved.visibility() == Visibility.PUBLIC && followedAgentIds.contains(resolved.id());
      if (!reachable) {
        return Decision.refused(
            "Agent '" + requested + "' is not accessible to this team. "
                + "Use lead_follow_agent to follow it first.");
      }
    }
    return new Decision(true, resolved.id(), null);
  }

  public record Decision(boolean allowed, String agentId, String refusal) {
    static Decision refused(String reason) {
      return new Decision(false, null, reason);
    }
  }
}
