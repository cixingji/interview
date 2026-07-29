package interview.guide.modules.training.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练创建时复制的一条历史答题证据。
 *
 * <p>这里保存的是快照而不是对历史答案实体的引用。即使原面试记录随后被删除或重新评估，
 * 已经开始的训练仍会基于创建时看到的事实继续运行，保证同一训练会话的诊断上下文稳定。
 *
 * @param sourceAnswerId 原答案主键，仅供服务端追踪，不下发给前端
 * @param sourceSessionId 原面试公开会话 ID
 * @param question 原问题
 * @param userAnswer 用户当时的回答
 * @param score 原回答数值评分，范围 0 到 100
 * @param feedback 原评估反馈
 * @param referenceAnswer 原参考答案
 * @param keyPoints 原评估关键点；历史 JSON 无效时为空列表
 * @param answeredAt 原回答时间
 */
public record TrainingEvidenceSnapshot(
    Long sourceAnswerId,
    String sourceSessionId,
    String question,
    String userAnswer,
    int score,
    String feedback,
    String referenceAnswer,
    List<String> keyPoints,
    LocalDateTime answeredAt
) {
}
