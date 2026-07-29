package interview.guide.modules.training.model;

import java.math.BigDecimal;

/**
 * 可对外展示的弱项主题摘要。
 *
 * <p>DTO 故意不包含 evidenceJson，避免历史回答、内部反馈和参考答案通过会话接口泄露。
 */
public record TrainingTopicDTO(
    String topicKey,
    String displayName,
    BigDecimal originalAverageScore,
    int sampleCount,
    int priorityRank,
    TrainingTopicStatus status,
    int questionCount
) {
}
