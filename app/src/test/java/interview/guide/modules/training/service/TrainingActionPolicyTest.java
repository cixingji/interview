package interview.guide.modules.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.training.model.TrainingAction;
import interview.guide.modules.training.model.TrainingActionState;
import interview.guide.modules.training.model.TrainingActionState.TopicState;
import interview.guide.modules.training.model.TrainingDecisionCandidate;
import interview.guide.modules.training.model.TrainingResolvedDecision;
import interview.guide.modules.training.model.TrainingTopicStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ReAct 训练动作安全策略")
class TrainingActionPolicyTest {

  private final TrainingActionPolicy policy = new TrainingActionPolicy();

  @Nested
  @DisplayName("服务端动作白名单")
  class AllowedActions {

    @Test
    @DisplayName("首题只能创建新问题")
    void initialTaskOnlyAllowsNewQuestion() {
      TrainingActionState state = state(true, 0, 0, 0, 0, "java");

      assertThat(policy.allowedActions(state))
          .containsExactly(TrainingAction.ASK_NEW_QUESTION);
    }

    @Test
    @DisplayName("达到题数上限后只能结束训练")
    void questionLimitOnlyAllowsFinish() {
      TrainingActionState state = state(false, 10, 2, 1, 0, "java");

      assertThat(policy.allowedActions(state))
          .containsExactly(TrainingAction.FINISH);
    }

    @Test
    @DisplayName("同主题连续题数达到上限时必须切换到其他未完成主题")
    void consecutiveLimitForcesTopicSwitch() {
      TrainingActionState state = state(false, 4, 3, 1, 3, "java");

      assertThat(policy.allowedActions(state))
          .containsExactly(TrainingAction.SWITCH_TOPIC);
    }

    @Test
    @DisplayName("未覆盖最低主题数时不允许模型提前结束")
    void minimumTopicCoverageBlocksEarlyFinish() {
      TrainingActionState state = state(false, 4, 1, 2, 0, "java");

      assertThat(policy.allowedActions(state))
          .doesNotContain(TrainingAction.FINISH)
          .contains(
              TrainingAction.FOLLOW_UP,
              TrainingAction.REINFORCE,
              TrainingAction.ASK_NEW_QUESTION,
              TrainingAction.SWITCH_TOPIC
          );
    }
  }

  @Nested
  @DisplayName("模型输出确定性修正")
  class CandidateResolution {

    @Test
    @DisplayName("未知动作、越界分数和伪造主题不会直接执行")
    void invalidCandidateFallsBackWithinServerBoundary() {
      TrainingActionState state = state(false, 3, 2, 1, 0, "java");
      TrainingDecisionCandidate candidate = new TrainingDecisionCandidate(
          "delete_database",
          "forged-topic",
          -20,
          null,
          null,
          null
      );

      TrainingResolvedDecision resolved = policy.resolve(state, candidate);

      assertThat(resolved.action()).isEqualTo(TrainingAction.FOLLOW_UP);
      assertThat(resolved.targetTopicKey()).isEqualTo("java");
      assertThat(resolved.score()).isZero();
      assertThat(resolved.feedback()).isNotBlank();
      assertThat(resolved.referenceAnswer()).isNotBlank();
      assertThat(resolved.nextQuestion()).isNotBlank();
      assertThat(resolved.fallbackApplied()).isTrue();
    }

    @Test
    @DisplayName("切换主题时只能选择诊断快照中的未完成主题")
    void switchTopicRejectsUnknownTarget() {
      TrainingActionState state = state(false, 3, 2, 1, 0, "java");
      TrainingDecisionCandidate candidate = new TrainingDecisionCandidate(
          "SWITCH_TOPIC",
          "outside-snapshot",
          80,
          "回答反馈",
          "参考回答",
          "下一题"
      );

      TrainingResolvedDecision resolved = policy.resolve(state, candidate);

      assertThat(resolved.action()).isEqualTo(TrainingAction.SWITCH_TOPIC);
      assertThat(resolved.targetTopicKey()).isEqualTo("database");
      assertThat(resolved.fallbackApplied()).isTrue();
    }

    @Test
    @DisplayName("结束动作会清除模型附带的主题和下一题")
    void finishStripsQuestionAndTopic() {
      TrainingActionState state = state(false, 10, 2, 1, 0, "java");
      TrainingDecisionCandidate candidate = new TrainingDecisionCandidate(
          "FINISH",
          "java",
          88,
          "回答反馈",
          "参考回答",
          "不应继续出现的问题"
      );

      TrainingResolvedDecision resolved = policy.resolve(state, candidate);

      assertThat(resolved.action()).isEqualTo(TrainingAction.FINISH);
      assertThat(resolved.targetTopicKey()).isNull();
      assertThat(resolved.nextQuestion()).isNull();
      assertThat(resolved.fallbackApplied()).isTrue();
    }
  }

  private TrainingActionState state(
      boolean initialTask,
      int questionCount,
      int coveredTopicCount,
      int minimumTopicCount,
      int consecutiveTopicQuestionCount,
      String currentTopicKey
  ) {
    return new TrainingActionState(
        initialTask,
        10,
        3,
        2,
        minimumTopicCount,
        questionCount,
        coveredTopicCount,
        currentTopicKey,
        initialTask ? null : 1,
        consecutiveTopicQuestionCount,
        0,
        initialTask ? null : currentTopicKey,
        List.of(
            new TopicState(
                "java",
                "Java 基础",
                1,
                "java".equals(currentTopicKey)
                    ? TrainingTopicStatus.ACTIVE
                    : TrainingTopicStatus.PENDING
            ),
            new TopicState(
                "database",
                "数据库",
                2,
                TrainingTopicStatus.PENDING
            )
        )
    );
  }
}
