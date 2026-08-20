package io.akka.intentkit.domain;

import java.util.List;

/**
 * One turn an agent is configured to take: either a failure, or the messages it answers with.
 *
 * <p>This is where the language model would be. SPEC-001 decision 5 keeps it out, so that
 * every rule about what a run records stays checkable and no timing contains an inference
 * call.
 *
 * <p>{@code delayMillis} is how long the turn takes. A run that overlaps its own schedule
 * cannot be observed without one, and the source-side probes script the same thing.
 */
public record ScriptedTurn(String failWith, long delayMillis, List<ReplyMessage> replies) {

  public ScriptedTurn {
    replies = replies == null ? List.of() : List.copyOf(replies);
  }

  /** A turn that answers straight away, which is what most of them do. */
  public ScriptedTurn(String failWith, List<ReplyMessage> replies) {
    this(failWith, 0, replies);
  }

  public static ScriptedTurn answering(String text) {
    return new ScriptedTurn(null, 0, List.of(ReplyMessage.of(AuthorType.AGENT, text)));
  }

  /**
   * The turn for a given index, with the last entry repeating.
   *
   * <p>An agent with no script at all echoes what it was asked, so a caller can always tell
   * which agent a prompt reached.
   */
  public static ScriptedTurn at(
      List<ScriptedTurn> script, long index, String agentId, String prompt) {
    if (script.isEmpty()) {
      return answering(agentId + " handled: " + prompt);
    }
    return script.get((int) Math.min(index, script.size() - 1L));
  }
}
