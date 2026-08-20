package io.akka.intentkit.domain;

/** A task's runtime status — SPEC-001 rules 8, 29. Absent is a fourth state, modelled as null. */
public enum TaskStatus {
  WAITING,
  RUNNING,
  ERROR;

  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
