package io.akka.intentkit.domain;

import java.time.Instant;

/** What a delegation decision needs to know about an agent, and nothing more. */
public record AgentSummary(
    String id, String teamId, String slug, Visibility visibility, Instant archivedAt) {

  public boolean archived() {
    return archivedAt != null;
  }
}
