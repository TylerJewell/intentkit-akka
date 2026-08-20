package io.akka.intentkit.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A cluster: who owns it, which agents are on its roster, which external agents it follows,
 * and which of its own agents its lead delegates to.
 *
 * <p>The owner matters at fire time, not only at creation: intentkit's sweep refuses to
 * schedule a task whose team has no owner and prunes any job it had (question-log #15). This
 * port makes the same check when the timer fires — SPEC-001 rules 15, 33.
 */
public record ClusterState(
    String teamId,
    String ownerUserId,
    Map<String, String> agentSlugs,
    List<String> followedAgentIds,
    List<String> leadDelegatesTo,
    List<String> taskIds,
    boolean exists) {

  public ClusterState {
    agentSlugs = agentSlugs == null ? Map.of() : Map.copyOf(agentSlugs);
    followedAgentIds = followedAgentIds == null ? List.of() : List.copyOf(followedAgentIds);
    leadDelegatesTo = leadDelegatesTo == null ? List.of() : List.copyOf(leadDelegatesTo);
    taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
  }

  public static ClusterState empty(String teamId) {
    return new ClusterState(teamId, null, Map.of(), List.of(), List.of(), List.of(), false);
  }

  /** The synthetic agent a lead-orchestrated run is attributed to (question-log #32). */
  public String leadAgentId() {
    return "team-" + teamId;
  }

  public ClusterState withOwner(String ownerUserId) {
    return new ClusterState(teamId, ownerUserId, agentSlugs, followedAgentIds, leadDelegatesTo,
        taskIds, true);
  }

  public ClusterState withAgent(String agentId, String slug) {
    var next = new LinkedHashMap<>(agentSlugs);
    next.put(agentId, slug == null ? agentId : slug);
    return new ClusterState(teamId, ownerUserId, next, followedAgentIds, leadDelegatesTo,
        taskIds, exists);
  }

  public ClusterState withoutAgent(String agentId) {
    var next = new LinkedHashMap<>(agentSlugs);
    next.remove(agentId);
    return new ClusterState(teamId, ownerUserId, next, followedAgentIds, leadDelegatesTo,
        taskIds, exists);
  }

  /**
   * Which agent a delegation target names, if this cluster can name it at all.
   *
   * <p>An agent on the roster answers to its id or its slug; a followed external agent
   * answers to its id only, because a follow is recorded by id and this cluster holds no
   * roster for another cluster. intentkit resolves a slug across every team's agents;
   * the narrower rule is listed in the published README.
   */
  public Optional<String> resolve(String target) {
    if (agentSlugs.containsKey(target) || followedAgentIds.contains(target)) {
      return Optional.of(target);
    }
    return agentSlugs.entrySet().stream()
        .filter(e -> e.getValue().equals(target))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  public List<String> agentIds() {
    return List.copyOf(agentSlugs.keySet());
  }

  public ClusterState following(String agentId) {
    return new ClusterState(teamId, ownerUserId, agentSlugs, plus(followedAgentIds, agentId),
        leadDelegatesTo, taskIds, exists);
  }

  public ClusterState unfollowing(String agentId) {
    return new ClusterState(teamId, ownerUserId, agentSlugs, minus(followedAgentIds, agentId),
        leadDelegatesTo, taskIds, exists);
  }

  public ClusterState delegatingTo(List<String> targets) {
    return new ClusterState(teamId, ownerUserId, agentSlugs, followedAgentIds,
        targets == null ? List.of() : List.copyOf(targets), taskIds, exists);
  }

  public ClusterState withTask(String taskId) {
    return new ClusterState(teamId, ownerUserId, agentSlugs, followedAgentIds, leadDelegatesTo,
        plus(taskIds, taskId), exists);
  }

  public ClusterState withoutTask(String taskId) {
    return new ClusterState(teamId, ownerUserId, agentSlugs, followedAgentIds, leadDelegatesTo,
        minus(taskIds, taskId), exists);
  }

  private static List<String> plus(List<String> list, String value) {
    if (list.contains(value)) {
      return list;
    }
    var out = new ArrayList<>(list);
    out.add(value);
    return List.copyOf(out);
  }

  private static List<String> minus(List<String> list, String value) {
    var out = new ArrayList<>(list);
    out.remove(value);
    return List.copyOf(out);
  }
}
