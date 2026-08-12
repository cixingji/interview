package interview.guide.modules.training.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建弱项训练会话请求。
 *
 * @param resumeId 可选的简历范围；为空时使用该训练方向下的通用历史证据
 * @param skillId 面试训练方向
 * @param llmProvider 可选的模型供应商；为空时使用系统默认供应商
 */
public record CreateTrainingSessionRequest(
    @Positive(message = "简历 ID 必须大于 0")
    Long resumeId,

    @NotBlank(message = "面试训练方向不能为空")
    @Size(max = 64, message = "面试训练方向长度不能超过 64 个字符")
    String skillId,

    @Size(max = 64, message = "模型供应商标识长度不能超过 64 个字符")
    String llmProvider
) {
}
