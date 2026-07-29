package interview.guide.modules.training.model;

/**
 * 训练会话生命周期。
 *
 * <p>状态只描述整场训练的业务阶段，不复用异步任务状态。一次训练可能包含多个轮次任务
 * 和一个总结任务，任务状态会在后续异步模块中单独持久化，避免一个字段同时表达两套状态机。
 */
public enum TrainingSessionStatus {
  READY,
  IN_PROGRESS,
  SUMMARIZING,
  COMPLETED,
  FAILED
}
