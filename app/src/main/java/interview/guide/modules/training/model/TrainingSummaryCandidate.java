package interview.guide.modules.training.model;

import java.util.List;

/**
 * 模型生成的训练总结文字候选。
 *
 * <p>该结构不包含总分或主题分。所有数值都由服务端根据已经持久化的 internalScore 聚合，
 * 防止模型通过结构化输出篡改训练结果。
 */
public record TrainingSummaryCandidate(
    String narrative,
    List<String> strengths,
    List<String> improvements,
    List<String> nextSteps
) {
}
