package interview.guide.modules.training.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练会话基础信息。
 *
 * <p>该 DTO 可被后续 Controller 直接复用，但不包含 ReAct 内部推理、工具参数、历史证据、
 * 内部评分或异常堆栈。
 */
public record TrainingSessionDTO(
    String trainingId,
    Long sourceResumeId,
    String sourceSkillId,
    TrainingSessionStatus status,
    int maxQuestions,
    int maxConsecutiveQuestionsPerTopic,
    int maxFollowUpsPerMainQuestion,
    int minimumTopicCount,
    int questionCount,
    int coveredTopicCount,
    List<TrainingTopicDTO> topics,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
