package interview.guide.modules.training.model;

/**
 * 最终结构化模型输出。
 *
 * <p>action 使用 String 而不是枚举，确保模型返回未知动作时仍能完成 JSON 解析，再由
 * 服务端策略确定性降级，避免仅因拼写错误重复调用 LLM。
 */
public record TrainingDecisionCandidate(
    String action,
    String targetTopicKey,
    Integer score,
    String feedback,
    String referenceAnswer,
    String nextQuestion
) {
}
