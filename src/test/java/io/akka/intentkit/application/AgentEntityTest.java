package io.akka.intentkit.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.intentkit.domain.AgentState;
import io.akka.intentkit.domain.AuthorType;
import io.akka.intentkit.domain.ReplyMessage;
import io.akka.intentkit.domain.ScriptedTurn;
import io.akka.intentkit.domain.Visibility;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 rule 38 — a thread belongs to one agent and one chat, and nothing else can reach
 * it. This is the invariant the whole per-agent half of the slice rests on.
 */
public class AgentEntityTest {

  private KeyValueEntityTestKit<AgentState, AgentEntity> agent(String id) {
    var kit = KeyValueEntityTestKit.of(id, AgentEntity::new);
    kit.method(AgentEntity::create)
        .invoke(new AgentEntity.Create("team-a", id + "-slug", id, Visibility.PRIVATE, List.of()));
    return kit;
  }

  @Test
  public void clearingOneChatLeavesTheOthersAlone() {
    var kit = agent("agent-1");
    kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("chat-a", "first"));
    kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("chat-b", "second"));

    kit.method(AgentEntity::clearThread).invoke("chat-a");

    assertThat(kit.method(AgentEntity::thread).invoke("chat-a").getReply()).isEmpty();
    assertThat(kit.method(AgentEntity::thread).invoke("chat-b").getReply()).isNotEmpty();
  }

  @Test
  public void clearingOneAgentsChatLeavesAnotherAgentsAlone() {
    var one = agent("agent-1");
    var two = agent("agent-2");
    one.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("shared", "hello"));
    two.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("shared", "hello"));

    one.method(AgentEntity::clearThread).invoke("shared");

    assertThat(one.method(AgentEntity::thread).invoke("shared").getReply()).isEmpty();
    assertThat(two.method(AgentEntity::thread).invoke("shared").getReply()).hasSize(2);
  }

  @Test
  public void clearingAChatThatWasNeverOpenedIsNotAnError() {
    var kit = agent("agent-1");
    var result = kit.method(AgentEntity::clearThread).invoke("never-used");
    assertThat(result.isError()).isFalse();
  }

  @Test
  public void aThreadHoldsThePromptAndEveryReply() {
    var kit = KeyValueEntityTestKit.of("agent-3", AgentEntity::new);
    kit.method(AgentEntity::create)
        .invoke(new AgentEntity.Create("team-a", "three", "Three", Visibility.PRIVATE,
            List.of(new ScriptedTurn(null,
                List.of(ReplyMessage.of(AuthorType.AGENT, "one"),
                        ReplyMessage.of(AuthorType.AGENT, "two"))))));

    kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("c", "go"));
    var thread = kit.method(AgentEntity::thread).invoke("c").getReply();
    assertThat(thread).hasSize(3);
    assertThat(thread.get(0).authorType()).isEqualTo(AuthorType.TRIGGER);
    assertThat(thread.get(0).message()).isEqualTo("go");
    assertThat(thread.get(2).message()).isEqualTo("two");
  }

  @Test
  public void anAgentWithNoScriptEchoesWhoReceivedThePrompt() {
    var kit = agent("agent-1");
    var turn = kit.method(AgentEntity::runTurn)
        .invoke(new AgentEntity.TurnRequest("c", "do it"))
        .getReply();
    assertThat(turn.failedWith()).isNull();
    assertThat(turn.replies()).singleElement()
        .extracting(ReplyMessage::message)
        .isEqualTo("agent-1 handled: do it");
  }

  @Test
  public void aScriptedFailureIsAnsweredRatherThanThrown() {
    var kit = KeyValueEntityTestKit.of("agent-4", AgentEntity::new);
    kit.method(AgentEntity::create)
        .invoke(new AgentEntity.Create("team-a", "four", "Four", Visibility.PRIVATE,
            List.of(new ScriptedTurn("model exploded", List.of()))));

    var result = kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("c", "go"));
    assertThat(result.isError()).isFalse();
    assertThat(result.getReply().failedWith()).isEqualTo("model exploded");
  }

  @Test
  public void theScriptAdvancesPerTurnAndItsLastEntryRepeats() {
    var kit = KeyValueEntityTestKit.of("agent-5", AgentEntity::new);
    kit.method(AgentEntity::create)
        .invoke(new AgentEntity.Create("team-a", "five", "Five", Visibility.PRIVATE,
            List.of(ScriptedTurn.answering("first"), ScriptedTurn.answering("rest"))));

    for (var expected : List.of("first", "rest", "rest")) {
      var turn = kit.method(AgentEntity::runTurn)
          .invoke(new AgentEntity.TurnRequest("c", "go"))
          .getReply();
      assertThat(turn.replies().get(0).message()).isEqualTo(expected);
    }
  }

  /** A new script starts at its first entry, not wherever the previous one had got to. */
  @Test
  public void replacingTheScriptRestartsIt() {
    var kit = KeyValueEntityTestKit.of("agent-6", AgentEntity::new);
    kit.method(AgentEntity::create)
        .invoke(new AgentEntity.Create("team-a", "six", "Six", Visibility.PRIVATE,
            List.of(ScriptedTurn.answering("old-first"),
                ScriptedTurn.answering("old-second"))));
    kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("c", "go"));

    kit.method(AgentEntity::create)
        .invoke(new AgentEntity.Create("team-a", "six", "Six", Visibility.PRIVATE,
            List.of(ScriptedTurn.answering("new-first"),
                ScriptedTurn.answering("new-second"))));
    var turn = kit.method(AgentEntity::runTurn)
        .invoke(new AgentEntity.TurnRequest("c", "go"))
        .getReply();
    assertThat(turn.replies().get(0).message()).isEqualTo("new-first");
  }

  /**
   * An agent delegated to over and over opens a chat per call and nothing closes them, so
   * the number of threads it holds has a ceiling.
   */
  @Test
  public void anAgentHoldsABoundedNumberOfChats() {
    var kit = agent("agent-7");
    for (int i = 0; i < AgentState.CHAT_LIMIT + 10; i++) {
      kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("call-" + i, "go"));
    }
    assertThat(kit.getState().threads()).hasSize(AgentState.CHAT_LIMIT);
    assertThat(kit.method(AgentEntity::thread).invoke("call-0").getReply()).isEmpty();
    var last = "call-" + (AgentState.CHAT_LIMIT + 9);
    assertThat(kit.method(AgentEntity::thread).invoke(last).getReply()).isNotEmpty();
  }

  /**
   * The chat evicted is the one written longest ago, not the one opened longest ago — so a
   * chat still in use is not dropped out from under the conversation it is holding.
   */
  @Test
  public void writingToAChatMovesItToTheBackOfTheEvictionOrder() {
    var kit = agent("agent-9");
    for (int i = 0; i < AgentState.CHAT_LIMIT; i++) {
      kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("call-" + i, "go"));
    }
    // The oldest chat, used again just before one more chat pushes the map over its ceiling.
    kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("call-0", "again"));
    kit.method(AgentEntity::runTurn).invoke(new AgentEntity.TurnRequest("call-new", "go"));

    assertThat(kit.getState().threads()).hasSize(AgentState.CHAT_LIMIT);
    assertThat(kit.method(AgentEntity::thread).invoke("call-0").getReply()).isNotEmpty();
    assertThat(kit.method(AgentEntity::thread).invoke("call-1").getReply()).isEmpty();
  }

  /** A failure record per failed run, and an agent that fails forever does not grow forever. */
  @Test
  public void activitiesAreBounded() {
    var kit = agent("agent-8");
    for (int i = 0; i < AgentState.ACTIVITY_LIMIT + 10; i++) {
      kit.method(AgentEntity::recordActivity).invoke("failure " + i);
    }
    var activities = kit.method(AgentEntity::activities).invoke().getReply();
    assertThat(activities).hasSize(AgentState.ACTIVITY_LIMIT);
    assertThat(activities.get(activities.size() - 1))
        .isEqualTo("failure " + (AgentState.ACTIVITY_LIMIT + 9));
  }

  @Test
  public void anArchivedAgentReportsItself() {
    var kit = agent("agent-1");
    kit.method(AgentEntity::archive).invoke();
    assertThat(kit.method(AgentEntity::summary).invoke().getReply().archived()).isTrue();
  }

  @Test
  public void anAgentThatWasNeverCreatedRefusesEveryQuestionAboutIt() {
    var kit = KeyValueEntityTestKit.of("ghost", AgentEntity::new);
    assertThat(kit.method(AgentEntity::summary).invoke().isError()).isTrue();
    assertThat(kit.method(AgentEntity::runTurn)
        .invoke(new AgentEntity.TurnRequest("c", "go")).isError()).isTrue();
  }
}
