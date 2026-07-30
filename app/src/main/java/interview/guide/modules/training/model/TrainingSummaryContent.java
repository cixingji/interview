package interview.guide.modules.training.model;

import java.util.List;

/**
 * 完成长度限制、空值补全和确定性降级后的总结文字。
 */
public record TrainingSummaryContent(
    String narrative,
    List<String> strengths,
    List<String> improvements,
    List<String> nextSteps,
    boolean fallbackApplied
) {
}
