package interview.guide.modules.training.model;

import java.time.LocalDateTime;

/**
 * 面向前端的训练轮次响应。
 *
 * <p>该类型刻意不包含 internalScore、parentTurn 数据库主键、工具观察和模型推理。
 * feedback 与 referenceAnswer 只有异步任务完成后才会有值，可用于向用户立即展示反馈。
 */
public record TrainingTurnResponse(
    String turnId,
    int turnIndex,
    int mainQuestionIndex,
    TrainingAction action,
    String topicKey,
    String question,
    String userAnswer,
    String feedback,
    String referenceAnswer,
    TrainingTurnStatus status,
    LocalDateTime createdAt,
    LocalDateTime answeredAt,
    LocalDateTime completedAt
) {
}
