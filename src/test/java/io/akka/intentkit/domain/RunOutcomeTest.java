package io.akka.intentkit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rules 24 and 25 — what a finished run says happened, and what it counts.
 *
 * <p>Every expectation here is a figure probe 03 read back out of a real execution row after
 * driving intentkit's own runner (question-log #27, #28, #29).
 */
public class RunOutcomeTest {

  private static ReplyMessage agent(String text) {
    return ReplyMessage.of(AuthorType.AGENT, text);
  }

  @Test
  public void lastMessageFromTheAgentIsSuccess() {
    var outcome = RunOutcome.of(List.of(agent("step one"), agent("step two")));
    assertThat(outcome.status()).isEqualTo(ExecutionStatus.SUCCESS);
    assertThat(outcome.error()).isNull();
    assertThat(outcome.stats().result()).isEqualTo("step two");
  }

  @Test
  public void lastMessageFromTheSystemIsTheRunsError() {
    var outcome = RunOutcome.of(List.of(agent("partial"),
        ReplyMessage.of(AuthorType.SYSTEM, "rate limited")));
    assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
    assertThat(outcome.error()).isEqualTo("Task execution error: rate limited");
    // The recorded result stays the last *agent* message, not the system error.
    assertThat(outcome.stats().result()).isEqualTo("partial");
  }

  @Test
  public void noMessagesAtAllIsItsOwnError() {
    var outcome = RunOutcome.of(List.of());
    assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
    assertThat(outcome.error()).isEqualTo("Unexpected result: empty response");
    assertThat(outcome.stats().result()).isNull();
  }

  @Test
  public void anyOtherLastAuthorIsUnexpected() {
    for (var author : List.of(AuthorType.TRIGGER, AuthorType.INTERNAL)) {
      var outcome = RunOutcome.of(List.of(ReplyMessage.of(author, "not an answer")));
      assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
      assertThat(outcome.error()).isEqualTo("Unexpected return error");
    }
  }

  @Test
  public void anAgentThatThrowsIsRecordedWithItsException() {
    var outcome = RunOutcome.ofFailure(new IllegalStateException("model exploded"));
    assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
    assertThat(outcome.error())
        .isEqualTo("Autonomous task exception: IllegalStateException('model exploded')");
    assertThat(outcome.stats().messageCount()).isZero();
  }

  @Test
  public void creditAddsMessageCostAndEveryToolCallsOwnCost() {
    var messages = List.of(
        new ReplyMessage(AuthorType.AGENT, "step one", 10, 3, 2,
            new BigDecimal("1.5"), 0.75,
            List.of(new ToolCall("x", new BigDecimal("0.25")))),
        new ReplyMessage(AuthorType.AGENT, "step two", 5, 7, 0,
            new BigDecimal("2.0"), 0.0, List.of()));

    var stats = RunOutcome.of(messages).stats();
    assertThat(stats.inputTokens()).isEqualTo(15);
    assertThat(stats.outputTokens()).isEqualTo(10);
    assertThat(stats.cachedInputTokens()).isEqualTo(2);
    assertThat(stats.creditCost()).isEqualByComparingTo(new BigDecimal("3.75"));
    assertThat(stats.messageCount()).isEqualTo(2);
    assertThat(stats.coldStartCost()).isEqualTo(0.75);
  }

  @Test
  public void aRunWithNoCostAtAllReportsNoCredit() {
    var stats = RunOutcome.of(List.of(agent("free"))).stats();
    assertThat(stats.creditCost()).isNull();
  }

  @Test
  public void theRecordedResultIsTruncatedAtFiveHundredCharacters() {
    var stats = RunOutcome.of(List.of(agent("R".repeat(800)))).stats();
    assertThat(stats.result()).hasSize(500).isEqualTo("R".repeat(500));
  }

  @Test
  public void coldStartCostIsTheFirstNonZeroOne() {
    var messages = List.of(
        new ReplyMessage(AuthorType.AGENT, "a", 0, 0, 0, null, 0.0, List.of()),
        new ReplyMessage(AuthorType.AGENT, "b", 0, 0, 0, null, 1.25, List.of()),
        new ReplyMessage(AuthorType.AGENT, "c", 0, 0, 0, null, 9.0, List.of()));
    assertThat(RunOutcome.of(messages).stats().coldStartCost()).isEqualTo(1.25);
  }
}
