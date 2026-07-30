package interview.guide.modules.training.model;

import java.time.LocalDateTime;

/**
 * 可供前端轮询的训练任务摘要。
 */
public record TrainingTaskDTO(
    String taskId,
    String trainingId,
    String sourceTurnId,
    TrainingTaskType taskType,
    TrainingTaskStatus status,
    int attemptCount,
    String safeErrorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt
) {
}
