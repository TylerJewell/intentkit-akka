package io.akka.intentkit.domain;

import java.math.BigDecimal;
import java.util.List;

/** One message an agent produced during a run. */
public record ReplyMessage(
    AuthorType authorType,
    String message,
    long inputTokens,
    long outputTokens,
    long cachedInputTokens,
    BigDecimal creditCost,
    double coldStartCost,
    List<ToolCall> toolCalls) {

  public ReplyMessage {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static ReplyMessage of(AuthorType authorType, String message) {
    return new ReplyMessage(authorType, message, 0, 0, 0, null, 0.0, List.of());
  }
}
