package io.akka.intentkit.domain;

/**
 * A schedule intentkit would not store, carrying the source's own refusal key.
 *
 * <p>The key is part of the contract, not a detail: a caller distinguishes "this is not a
 * cron expression" from "this cron expression runs too often" by reading it, and both
 * arrive as HTTP 400.
 */
public class ScheduleRejected extends RuntimeException {

  private final String key;

  public ScheduleRejected(String key, String message) {
    super(message);
    this.key = key;
  }

  public String key() {
    return key;
  }
}
