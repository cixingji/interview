package interview.guide.modules.training.service;

import interview.guide.modules.training.model.TrainingSummaryCandidate;
import interview.guide.modules.training.model.TrainingSummaryContent;
import interview.guide.modules.training.model.TrainingSummaryContext;
import interview.guide.modules.training.model.TrainingSummaryTopicResult;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对模型生成的总结文字执行长度限制、去重和确定性降级。
 */
@Component
public class TrainingSummaryPolicy {

  private static final int MAX_NARRATIVE_CHARS = 4_000;
  private static final int MAX_LIST_ITEMS = 5;
  private static final int MAX_ITEM_CHARS = 500;

  public TrainingSummaryContent resolve(
      TrainingSummaryContext context,
      TrainingSummaryCandidate candidate
  ) {
    String narrative = normalizeText(
        candidate == null ? null : candidate.narrative(),
        MAX_NARRATIVE_CHARS
    );
    List<String> strengths = normalizeItems(
        candidate == null ? null : candidate.strengths()
    );
    List<String> improvements = normalizeItems(
        candidate == null ? null : candidate.improvements()
    );
    List<String> nextSteps = normalizeItems(
        candidate == null ? null : candidate.nextSteps()
    );
    boolean fallback = false;

    if (narrative == null) {
      narrative = buildFallbackNarrative(context.overallScore());
      fallback = true;
    }
    if (strengths.isEmpty()) {
      strengths = List.of(
          "在“" + strongestTopic(context).displayName() + "”主题中表现相对稳定。"
      );
      fallback = true;
    }
    if (improvements.isEmpty()) {
      improvements = List.of(
          "优先补强“" + weakestTopic(context).displayName() + "”主题的原理、边界和实践依据。"
      );
      fallback = true;
    }
    if (nextSteps.isEmpty()) {
      nextSteps = List.of(
          "复盘本次反馈并整理每个弱项主题的核心知识框架。",
          "针对最低分主题重新完成一道同类题并对照参考答案修正。"
      );
      fallback = true;
    }
    return new TrainingSummaryContent(
        narrative,
        strengths,
        improvements,
        nextSteps,
        fallback
    );
  }

  private String buildFallbackNarrative(int overallScore) {
    if (overallScore >= 80) {
      return "本次训练整体表现良好，主要知识点掌握较稳定。后续应继续补充边界条件、"
          + "方案权衡和项目实践细节，使回答更完整。";
    }
    if (overallScore >= 60) {
      return "本次训练已覆盖主要弱项，但部分回答在原理深度、适用边界和实践依据上仍有"
          + "提升空间。建议按照主题结果逐项复盘。";
    }
    return "本次训练暴露出若干基础知识和表达结构上的薄弱点。建议先补齐最低分主题的"
        + "核心概念，再通过同类问题进行重复练习。";
  }

  private TrainingSummaryTopicResult strongestTopic(TrainingSummaryContext context) {
    return context.topics().stream()
        .max(Comparator.comparingInt(TrainingSummaryTopicResult::trainingAverageScore))
        .orElse(context.topics().getFirst());
  }

  private TrainingSummaryTopicResult weakestTopic(TrainingSummaryContext context) {
    return context.topics().stream()
        .min(Comparator.comparingInt(TrainingSummaryTopicResult::trainingAverageScore))
        .orElse(context.topics().getFirst());
  }

  private List<String> normalizeItems(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      String item = normalizeText(value, MAX_ITEM_CHARS);
      if (item != null) {
        normalized.add(item);
      }
      if (normalized.size() >= MAX_LIST_ITEMS) {
        break;
      }
    }
    return List.copyOf(normalized);
  }

  private String normalizeText(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    return normalized.length() <= maxLength
        ? normalized
        : normalized.substring(0, maxLength);
  }
}
