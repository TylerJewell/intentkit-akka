package io.akka.intentkit.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import io.akka.intentkit.domain.AgentState;
import io.akka.intentkit.domain.AgentSummary;
import io.akka.intentkit.domain.ReplyMessage;
import io.akka.intentkit.domain.ScriptedTurn;
import io.akka.intentkit.domain.Visibility;
import java.time.Instant;
import java.util.List;

/**
 * One agent — SPEC-001 rules 23, 32, 38.
 *
 * <p>An entity per agent rather than a row per agent, because the isolation this slice is
 * about is per agent: the runtime serialises commands to one instance, which was measured
 * granting exactly one of sixteen simultaneous claims (question-log T3). One agent's threads
 * cannot be touched by a command aimed at another.
 */
@Component(id = "agent")
public class AgentEntity extends KeyValueEntity<AgentState> {

  private final String agentId;

  public AgentEntity(KeyValueEntityContext context) {
    this.agentId = context.entityId();
  }

  @Override
  public AgentState emptyState() {
    return AgentState.empty(agentId);
  }

  public record Create(
      String teamId, String slug, String name, Visibility visibility, List<ScriptedTurn> script) {}

  public record TurnRequest(String chatId, String prompt) {}

  /**
   * What one turn produced, whether the agent refused to take it, and how long it says it
   * took. The caller waits out {@code delayMillis}; waiting here would block the entity,
   * which is not what a slow agent does to the rest of the system.
   */
  public record TurnResult(List<ReplyMessage> replies, String failedWith, long delayMillis) {}

  public Effect<Done> create(Create command) {
    var slug = command.slug() == null ? agentId : command.slug();
    var visibility = command.visibility() == null ? Visibility.PRIVATE : command.visibility();
    return effects()
        .updateState(
            currentState().created(command.teamId(), slug, command.name(), visibility,
                command.script()))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> archive() {
    if (!currentState().exists()) {
      return effects().error("Agent '" + agentId + "' not found");
    }
    return effects()
        .updateState(currentState().archived(Instant.now()))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<AgentSummary> summary() {
    if (!currentState().exists()) {
      return effects().error("Agent '" + agentId + "' not found");
    }
    return effects().reply(currentState().summary());
  }

  /**
   * Take one turn on a chat.
   *
   * <p>A scripted failure is answered rather than thrown: throwing here would make the
   * runtime re-deliver the command, and a run that failed because the agent failed is a
   * recorded outcome, not a lost command.
   */
  public Effect<TurnResult> runTurn(TurnRequest request) {
    if (!currentState().exists()) {
      return effects().error("Agent '" + agentId + "' not found");
    }
    var turn = currentState().takeTurn(request.chatId(), request.prompt());
    return effects()
        .updateState(turn.state())
        .thenReply(new TurnResult(turn.scripted().replies(), turn.scripted().failWith(),
            turn.scripted().delayMillis()));
  }

  /** Wipe one chat's thread. A chat that was never opened is not an error. */
  public Effect<Done> clearThread(String chatId) {
    return effects()
        .updateState(currentState().threadCleared(chatId))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<List<ReplyMessage>> thread(String chatId) {
    return effects().reply(currentState().thread(chatId));
  }

  /** The failure record intentkit writes to agent_activities alongside a failed run. */
  public Effect<Done> recordActivity(String text) {
    return effects()
        .updateState(currentState().withActivity(text))
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<List<String>> activities() {
    return effects().reply(currentState().activities());
  }
}
