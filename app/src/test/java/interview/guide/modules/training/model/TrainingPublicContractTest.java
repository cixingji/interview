package interview.guide.modules.training.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReAct 训练公开响应隐私契约")
class TrainingPublicContractTest {

  private static final Set<String> FORBIDDEN_PUBLIC_FIELDS = Set.of(
      "internalScore",
      "evidenceJson",
      "failureMessage",
      "parentTurnId",
      "databaseId",
      "toolCalls",
      "observations",
      "thought"
  );

  @Test
  @DisplayName("会话、主题、任务、轮次和总结响应不包含内部字段")
  void publicResponsesExcludeInternalFields() {
    assertPublicRecord(TrainingSessionDTO.class);
    assertPublicRecord(TrainingTopicDTO.class);
    assertPublicRecord(TrainingTaskDTO.class);
    assertPublicRecord(TrainingTaskPollResponse.class);
    assertPublicRecord(TrainingTurnResponse.class);
    assertPublicRecord(TrainingSummaryResponse.class);
    assertPublicRecord(TrainingSummaryTopicResult.class);
  }

  @Test
  @DisplayName("总结 Prompt 的单题快照不携带内部评分")
  void summaryTurnContextExcludesPerTurnScore() {
    assertThat(componentNames(TrainingSummaryContext.TurnSummary.class))
        .containsExactlyInAnyOrder("turnIndex", "topicKey", "question", "feedback")
        .doesNotContain("internalScore", "score");
  }

  private void assertPublicRecord(Class<?> recordType) {
    assertThat(recordType.isRecord())
        .as("%s 必须保持为显式白名单 record", recordType.getSimpleName())
        .isTrue();
    assertThat(componentNames(recordType))
        .as("%s 不得暴露内部实现字段", recordType.getSimpleName())
        .doesNotContainAnyElementsOf(FORBIDDEN_PUBLIC_FIELDS);
  }

  private Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }
}
