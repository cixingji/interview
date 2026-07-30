package interview.guide.modules.training.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练完成后对外展示的总结。
 *
 * <p>这是唯一展示训练评分的接口，但仍只展示整场和主题聚合分数，不返回任何单题
 * internalScore、模型原始输出、工具调用记录或诊断 evidenceJson。
 */
public record TrainingSummaryResponse(
    String trainingId,
    int overallScore,
    int completedQuestionCount,
    int coveredTopicCount,
    String narrative,
    List<String> strengths,
    List<String> improvements,
    List<String> nextSteps,
    List<TrainingSummaryTopicResult> topics,
    LocalDateTime generatedAt
) {
}
