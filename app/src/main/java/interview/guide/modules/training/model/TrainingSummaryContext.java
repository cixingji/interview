package interview.guide.modules.training.model;

import java.util.List;

/**
 * 总结任务在只读事务中形成的不可变统计快照。
 *
 * <p>Runner 离开事务后只使用该对象调用 LLM，不持有 Entity 或数据库连接。单题内部评分
 * 只在 ContextService 计算聚合值时短暂使用，不进入该上下文、Prompt 或最终公开响应。
 */
public record TrainingSummaryContext(
    String taskId,
    String trainingId,
    String llmProvider,
    int overallScore,
    int completedQuestionCount,
    int coveredTopicCount,
    List<TrainingSummaryTopicResult> topics,
    List<TurnSummary> turns
) {

  public record TurnSummary(
      int turnIndex,
      String topicKey,
      String question,
      String feedback
  ) {
  }
}
