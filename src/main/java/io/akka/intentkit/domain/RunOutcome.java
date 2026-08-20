package io.akka.intentkit.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a finished run says happened — SPEC-001 rules 24 and 25.
 *
 * <p>The outcome is decided from the last message's author and nothing else: an agent
 * answered, the system reported a failure, or something arrived that a run is not supposed
 * to end with. The recorded {@code result} is the last *agent* message even when the run
 * failed, which is what intentkit records (question-log #29).
 */
public record RunOutcome(ExecutionStatus status, String error, RunStats stats) {

  /** Longer replies are cut here on the execution record; the messages themselves are whole. */
  public static final int RESULT_MAX_LENGTH = 500;

  public static RunOutcome of(List<ReplyMessage> messages) {
    var stats = aggregate(messages);
    if (messages.isEmpty()) {
      return new RunOutcome(ExecutionStatus.ERROR, "Unexpected result: empty response", stats);
    }
    var last = messages.get(messages.size() - 1);
    return switch (last.authorType()) {
      case AGENT -> new RunOutcome(ExecutionStatus.SUCCESS, null, stats);
      case SYSTEM -> new RunOutcome(
          ExecutionStatus.ERROR, "Task execution error: " + last.message(), stats);
      default -> new RunOutcome(ExecutionStatus.ERROR, "Unexpected return error", stats);
    };
  }

  /**
   * An agent that threw rather than answered.
   *
   * <p>Rendered the way intentkit renders it — Python's {@code repr} of the exception — so
   * the two systems' execution rows carry the same text for the same failure.
   */
  public static RunOutcome ofFailure(Throwable failure) {
    var text =
        "Autonomous task exception: "
            + failure.getClass().getSimpleName()
            + "('"
            + failure.getMessage()
            + "')";
    return new RunOutcome(ExecutionStatus.ERROR, text, RunStats.empty());
  }

  /**
   * An agent whose script says to fail rather than answer.
   *
   * <p>Rendered as though a plain runtime exception had come out of the agent, so a scripted
   * failure and a real one are indistinguishable on the execution record.
   */
  public static RunOutcome ofFailure(String message) {
    return new RunOutcome(
        ExecutionStatus.ERROR,
        "Autonomous task exception: RuntimeException('" + message + "')",
        RunStats.empty());
  }

  private static RunStats aggregate(List<ReplyMessage> messages) {
    long input = 0;
    long output = 0;
    long cached = 0;
    var credit = BigDecimal.ZERO;
    boolean anyCredit = false;
    double coldStart = 0.0;
    String result = null;

    for (var m : messages) {
      input += m.inputTokens();
      output += m.outputTokens();
      cached += m.cachedInputTokens();
      if (m.creditCost() != null) {
        credit = credit.add(m.creditCost());
        anyCredit = true;
      }
      for (var call : m.toolCalls()) {
        if (call.creditCost() != null) {
          credit = credit.add(call.creditCost());
          anyCredit = true;
        }
      }
      if (coldStart == 0.0 && m.coldStartCost() != 0.0) {
        coldStart = m.coldStartCost();
      }
    }
    for (int i = messages.size() - 1; i >= 0; i--) {
      var m = messages.get(i);
      if (m.authorType() == AuthorType.AGENT && m.message() != null) {
        result = m.message().substring(0, Math.min(m.message().length(), RESULT_MAX_LENGTH));
        break;
      }
    }
    return new RunStats(
        input,
        output,
        cached,
        anyCredit && credit.signum() != 0 ? credit : null,
        messages.size(),
        coldStart,
        result);
  }
}
