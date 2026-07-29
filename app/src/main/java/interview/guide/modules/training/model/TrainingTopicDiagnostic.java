package interview.guide.modules.training.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 诊断工厂输出的单个弱项主题。
 *
 * <p>该对象只在创建事务内流转，随后会整体写入 {@link TrainingTopicEntity}。平均分基于该主题
 * 的全部有效样本计算，evidence 只保留最近的有限条证据，防止快照和后续 Prompt 无界增长。
 */
public record TrainingTopicDiagnostic(
    String topicKey,
    String displayName,
    BigDecimal averageScore,
    int sampleCount,
    List<TrainingEvidenceSnapshot> evidence
) {
}
