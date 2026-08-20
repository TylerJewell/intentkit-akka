package io.akka.intentkit.domain;

/**
 * Who produced a message. The outcome of a run is decided from the author of its last one
 * and nothing else — SPEC-001 rule 24.
 */
public enum AuthorType {
  AGENT,
  SYSTEM,
  TRIGGER,
  INTERNAL
}
