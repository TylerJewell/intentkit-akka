package io.akka.intentkit.domain;

import java.math.BigDecimal;

/** What a finished run is recorded as having used — SPEC-001 rule 25. */
public record RunStats(
    long inputTokens,
    long outputTokens,
    long cachedInputTokens,
    BigDecimal creditCost,
    int messageCount,
    double coldStartCost,
    String result) {

  public static RunStats empty() {
    return new RunStats(0, 0, 0, null, 0, 0.0, null);
  }
}
