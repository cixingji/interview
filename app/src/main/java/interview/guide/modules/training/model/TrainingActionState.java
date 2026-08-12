package interview.guide.modules.training.model;

import java.util.List;

/**
 * 计算 allowedActions 所需的最小可信状态，不包含 Prompt、历史回答或模型输出。
 */
public record TrainingActionState(
    boolean initialTask,
    int maxQuestions,
    int maxConsecutiveQuestionsPerTopic,
    int maxFollowUpsPerMainQuestion,
    int minimumTopicCount,
    int questionCount,
    int coveredTopicCount,
    String currentTopicKey,
    Integer currentMainQuestionIndex,
    int consecutiveTopicQuestionCount,
    int currentMainQuestionFollowUpCount,
    String sourceTopicKey,
    List<TopicState> topics
) {

  public record TopicState(
      String topicKey,
      String displayName,
      int priorityRank,
      TrainingTopicStatus status
  ) {
  }
}
