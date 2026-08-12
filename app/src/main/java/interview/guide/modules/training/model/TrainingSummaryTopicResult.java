package interview.guide.modules.training.model;

import java.math.BigDecimal;

/**
 * 单个训练主题的服务端聚合结果。
 *
 * @param originalAverageScore 训练创建时固化的历史诊断均分
 * @param trainingAverageScore 本次训练已完成题目的内部评分均值
 */
public record TrainingSummaryTopicResult(
    String topicKey,
    String displayName,
    BigDecimal originalAverageScore,
    int trainingAverageScore,
    int answeredQuestionCount
) {
}
