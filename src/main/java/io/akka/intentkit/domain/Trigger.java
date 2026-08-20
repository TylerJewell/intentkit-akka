package io.akka.intentkit.domain;

/** How a run was started — SPEC-001 rule 28. */
public enum Trigger {
  CRON,
  MANUAL;

  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
