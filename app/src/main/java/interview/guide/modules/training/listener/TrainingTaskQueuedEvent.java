package interview.guide.modules.training.listener;

/**
 * 数据库任务提交成功后需要投递到 Redis Stream 的领域事件。
 *
 * <p>事件在事务内发布，但监听器只在 AFTER_COMMIT 执行，避免 Redis 先收到消息而数据库
 * 事务最终回滚。Redis 投递失败时任务仍保留为 QUEUED，恢复调度会再次投递。
 */
public record TrainingTaskQueuedEvent(
    String taskId,
    String trainingId,
    int retryCount
) {
}
