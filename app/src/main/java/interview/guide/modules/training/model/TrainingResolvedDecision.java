package interview.guide.modules.training.model;

/**
 * 通过服务端 allowedActions、主题范围和字段长度校验后的可执行决定。
 */
public record TrainingResolvedDecision(
    TrainingAction action,
    String targetTopicKey,
    int score,
    String feedback,
    String referenceAnswer,
    String nextQuestion,
    boolean fallbackApplied
) {
}
