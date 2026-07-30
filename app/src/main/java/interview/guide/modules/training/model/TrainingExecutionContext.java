package interview.guide.modules.training.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次训练任务执行所需的不可变快照。
 *
 * <p>状态服务在只读事务中把 Entity 转成该对象，事务结束后 ReAct Runner 只操作普通内存
 * 数据并调用 LLM。这样不会在模型调用期间持有数据库连接，也不会把懒加载代理传进工具。
 */
public record TrainingExecutionContext(
    String taskId,
    String trainingId,
    TrainingTaskType taskType,
    String skillId,
    String llmProvider,
    TrainingSessionStatus sessionStatus,
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
    TurnSnapshot sourceTurn,
    List<TopicSnapshot> topics,
    List<TurnSnapshot> previousTurns
) {

  public boolean isInitialTask() {
    return taskType == TrainingTaskType.INITIAL_TURN;
  }

  public record TopicSnapshot(
      String topicKey,
      String displayName,
      BigDecimal originalAverageScore,
      int sampleCount,
      int priorityRank,
      TrainingTopicStatus status,
      int questionCount,
      List<TrainingEvidenceSnapshot> evidence
  ) {
  }

  /**
   * Runner 只读取完成轮次和当前待分析轮次；internalScore 永远不会进入对外轮次 DTO。
   */
  public record TurnSnapshot(
      String turnId,
      int turnIndex,
      Integer mainQuestionIndex,
      TrainingAction action,
      String topicKey,
      String question,
      String userAnswer,
      String feedback,
      Integer internalScore,
      String referenceAnswer,
      TrainingTurnStatus status
  ) {
  }
}
