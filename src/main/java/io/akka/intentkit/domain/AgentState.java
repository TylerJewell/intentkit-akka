package io.akka.intentkit.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One agent in a cluster: who it is, whether it can still be reached, what it answers, and
 * the conversations it is holding.
 *
 * <p>SPEC-001 rule 38. Threads are keyed by chat, and every run of a scheduled task wipes
 * exactly one of them — so nothing a task did last time, and nothing another task is doing
 * now, is visible to the run in progress. intentkit gets the same separation from a
 * composite key on its checkpoint tables (question-log #25, #26).
 *
 * <p>The {@code script} is what the language model is in intentkit. SPEC-001 decision 5: no
 * rule in this port is about what a model says, and a benchmark that called one would be
 * measuring the vendor.
 */
public record AgentState(
    String agentId,
    String teamId,
    String slug,
    String name,
    Visibility visibility,
    Instant archivedAt,
    List<ScriptedTurn> script,
    long turnsTaken,
    Map<String, List<ReplyMessage>> threads,
    List<String> activities,
    boolean exists) {

  /** A thread longer than this drops its oldest messages. */
  public static final int THREAD_LIMIT = 100;

  /**
   * How many chats one agent keeps threads for.
   *
   * <p>Every delegated call opens a chat of its own and nothing closes it, so without a
   * ceiling an agent that is delegated to often accumulates a thread per run for as long
   * as it lives. The oldest chat goes when the ceiling is reached.
   */
  public static final int CHAT_LIMIT = 50;

  /** How many failure records one agent keeps, newest last. */
  public static final int ACTIVITY_LIMIT = 100;

  public AgentState {
    script = script == null ? List.of() : List.copyOf(script);
    // A linked copy, not Map.copyOf: eviction takes the chat written longest ago, and
    // Map.copyOf returns an unordered map, so the order would be lost on every write and
    // the chat evicted would be an arbitrary one.
    threads = threads == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(threads));
    activities = activities == null ? List.of() : List.copyOf(activities);
  }

  public static AgentState empty(String agentId) {
    return new AgentState(agentId, null, null, null, Visibility.PRIVATE, null,
        List.of(), 0, Map.of(), List.of(), false);
  }

  public AgentSummary summary() {
    return new AgentSummary(agentId, teamId, slug, visibility, archivedAt);
  }

  /**
   * Give this agent an identity and a script.
   *
   * <p>The turn counter goes back to zero: it is an index into the script, so keeping it
   * across a new script would make the first turn afterwards land at an arbitrary entry.
   */
  public AgentState created(
      String teamId, String slug, String name, Visibility visibility, List<ScriptedTurn> script) {
    return new AgentState(agentId, teamId, slug, name, visibility, null,
        script, 0, threads, activities, true);
  }

  public AgentState archived(Instant at) {
    return new AgentState(agentId, teamId, slug, name, visibility, at,
        script, turnsTaken, threads, activities, exists);
  }

  /** The turn the script says to take next, appended to {@code chatId}'s thread. */
  public Turn takeTurn(String chatId, String prompt) {
    var scripted = ScriptedTurn.at(script, turnsTaken, agentId, prompt);
    var thread = new ArrayList<>(threads.getOrDefault(chatId, List.of()));
    thread.add(ReplyMessage.of(AuthorType.TRIGGER, prompt));
    thread.addAll(scripted.replies());
    while (thread.size() > THREAD_LIMIT) {
      thread.remove(0);
    }
    var newThreads = new LinkedHashMap<>(threads);
    newThreads.remove(chatId);
    newThreads.put(chatId, List.copyOf(thread));
    while (newThreads.size() > CHAT_LIMIT) {
      newThreads.remove(newThreads.keySet().iterator().next());
    }
    var next = new AgentState(agentId, teamId, slug, name, visibility, archivedAt,
        script, turnsTaken + 1, newThreads, activities, exists);
    return new Turn(next, scripted);
  }

  public AgentState threadCleared(String chatId) {
    if (!threads.containsKey(chatId)) {
      return this;
    }
    var newThreads = new LinkedHashMap<>(threads);
    newThreads.remove(chatId);
    return new AgentState(agentId, teamId, slug, name, visibility, archivedAt,
        script, turnsTaken, newThreads, activities, exists);
  }

  public AgentState withActivity(String text) {
    var next = new ArrayList<>(activities);
    next.add(text);
    while (next.size() > ACTIVITY_LIMIT) {
      next.remove(0);
    }
    return new AgentState(agentId, teamId, slug, name, visibility, archivedAt,
        script, turnsTaken, threads, List.copyOf(next), exists);
  }

  public List<ReplyMessage> thread(String chatId) {
    return threads.getOrDefault(chatId, List.of());
  }

  public record Turn(AgentState state, ScriptedTurn scripted) {}
}
