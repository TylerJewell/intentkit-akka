package io.akka.intentkit.domain;

/** The status of one run — SPEC-001 rules 20, 21, 24. */
public enum ExecutionStatus {
  RUNNING,
  SUCCESS,
  ERROR;

  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
