package interview.guide.modules.training.model;

/**
 * 弱项主题在本次训练中的覆盖状态。
 *
 * <p>诊断分数和历史证据创建后保持不变；该状态仅记录训练进度，不会回写或重算快照。
 */
public enum TrainingTopicStatus {
  PENDING,
  ACTIVE,
  COMPLETED
}
