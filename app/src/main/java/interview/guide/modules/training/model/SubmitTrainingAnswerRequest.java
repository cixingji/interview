package interview.guide.modules.training.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交训练回答请求。
 */
public record SubmitTrainingAnswerRequest(
    @NotBlank(message = "回答不能为空")
    @Size(max = 8_000, message = "回答长度不能超过 8000 个字符")
    String answer
) {
}
