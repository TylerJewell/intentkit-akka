package io.akka.intentkit.domain;

import java.math.BigDecimal;

/**
 * One tool a message invoked, and what it cost.
 *
 * <p>The cost is separate from the message's own because intentkit records the two as
 * separate credit events and a run's total is their sum (question-log #28).
 */
public record ToolCall(String name, BigDecimal creditCost) {}
