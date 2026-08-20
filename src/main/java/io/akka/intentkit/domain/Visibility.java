package io.akka.intentkit.domain;

/**
 * Whether an agent can be reached from outside its own cluster. Only a public agent can be
 * followed, and only a followed one can be delegated to — SPEC-001 rule 33.
 */
public enum Visibility {
  PRIVATE,
  PUBLIC
}
