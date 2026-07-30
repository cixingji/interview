package interview.guide.modules.training.model;

/**
 * 单个训练轮次的持久化状态。
 */
public enum TrainingTurnStatus {
  WAITING_ANSWER,
  QUEUED,
  PROCESSING,
  COMPLETED,
  FAILED
}
