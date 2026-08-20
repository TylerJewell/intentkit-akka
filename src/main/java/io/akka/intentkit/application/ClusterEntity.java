package io.akka.intentkit.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.intentkit.domain.ClusterState;
import java.util.List;

/** One cluster: its owner, its roster, its follow list and its schedules. */
@Component(id = "cluster")
public class ClusterEntity extends KeyValueEntity<ClusterState> {

  private final String teamId;

  public ClusterEntity(KeyValueEntityContext context) {
    this.teamId = context.entityId();
  }

  @Override
  public ClusterState emptyState() {
    return ClusterState.empty(teamId);
  }

  public Effect<Done> create(String ownerUserId) {
    return effects()
        .updateState(currentState().withOwner(ownerUserId))
        .thenReply(Done.getInstance());
  }

  /**
   * Remove the owner without removing the cluster.
   *
   * <p>Exists because it is the state intentkit's sweep tests for: a team row with no owner
   * member, whose tasks are skipped and whose jobs are pruned (question-log #15).
   */
  public Effect<Done> removeOwner() {
    return effects().updateState(currentState().withOwner(null)).thenReply(Done.getInstance());
  }

  public record AgentRef(String agentId, String slug) {}

  public Effect<Done> addAgent(AgentRef agent) {
    return effects()
        .updateState(currentState().withAgent(agent.agentId(), agent.slug()))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> removeAgent(String agentId) {
    return effects()
        .updateState(currentState().withoutAgent(agentId))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> follow(String agentId) {
    return effects().updateState(currentState().following(agentId)).thenReply(Done.getInstance());
  }

  public Effect<Done> unfollow(String agentId) {
    return effects()
        .updateState(currentState().unfollowing(agentId))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> setLeadDelegatesTo(List<String> targets) {
    return effects()
        .updateState(currentState().delegatingTo(targets))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> addTask(String taskId) {
    return effects().updateState(currentState().withTask(taskId)).thenReply(Done.getInstance());
  }

  public Effect<Done> removeTask(String taskId) {
    return effects().updateState(currentState().withoutTask(taskId)).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<ClusterState> get() {
    return effects().reply(currentState());
  }
}
