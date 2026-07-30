package interview.guide.modules.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.training.model.TrainingSummaryCandidate;
import interview.guide.modules.training.model.TrainingSummaryContent;
import interview.guide.modules.training.model.TrainingSummaryContext;
import interview.guide.modules.training.model.TrainingSummaryContext.TurnSummary;
import interview.guide.modules.training.model.TrainingSummaryTopicResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ReAct 训练总结内容策略")
class TrainingSummaryPolicyTest {

  private final TrainingSummaryPolicy policy = new TrainingSummaryPolicy();

  @Nested
  @DisplayName("模型文字清洗")
  class CandidateSanitization {

    @Test
    @DisplayName("列表去空、去重并限制为五项")
    void listItemsAreNormalizedAndLimited() {
      TrainingSummaryCandidate candidate = new TrainingSummaryCandidate(
          "  本次训练总结  ",
          List.of("亮点一", " ", "亮点一", "亮点二", "亮点三", "亮点四", "亮点五", "亮点六"),
          List.of("待加强一"),
          List.of("下一步一")
      );

      TrainingSummaryContent resolved = policy.resolve(context(), candidate);

      assertThat(resolved.narrative()).isEqualTo("本次训练总结");
      assertThat(resolved.strengths())
          .containsExactly("亮点一", "亮点二", "亮点三", "亮点四", "亮点五");
      assertThat(resolved.fallbackApplied()).isFalse();
    }

    @Test
    @DisplayName("过长叙述和列表项会在服务端边界内截断")
    void textLengthIsBounded() {
      TrainingSummaryCandidate candidate = new TrainingSummaryCandidate(
          "叙".repeat(4_100),
          List.of("优".repeat(600)),
          List.of("改进"),
          List.of("下一步")
      );

      TrainingSummaryContent resolved = policy.resolve(context(), candidate);

      assertThat(resolved.narrative()).hasSize(4_000);
      assertThat(resolved.strengths().getFirst()).hasSize(500);
    }
  }

  @Nested
  @DisplayName("确定性降级")
  class Fallback {

    @Test
    @DisplayName("模型返回空内容时仍生成完整且基于聚合主题的总结")
    void emptyCandidateUsesDeterministicSummary() {
      TrainingSummaryContent resolved = policy.resolve(context(), null);

      assertThat(resolved.narrative()).contains("提升空间");
      assertThat(resolved.strengths()).singleElement().asString().contains("Java 基础");
      assertThat(resolved.improvements()).singleElement().asString().contains("数据库");
      assertThat(resolved.nextSteps()).hasSize(2);
      assertThat(resolved.fallbackApplied()).isTrue();
    }
  }

  private TrainingSummaryContext context() {
    return new TrainingSummaryContext(
        "task-1",
        "training-1",
        null,
        68,
        4,
        2,
        List.of(
            new TrainingSummaryTopicResult(
                "java",
                "Java 基础",
                new BigDecimal("55.50"),
                82,
                2
            ),
            new TrainingSummaryTopicResult(
                "database",
                "数据库",
                new BigDecimal("48.00"),
                54,
                2
            )
        ),
        List.of(
            new TurnSummary(1, "java", "问题一", "反馈一"),
            new TurnSummary(2, "database", "问题二", "反馈二")
        )
    );
  }
}
