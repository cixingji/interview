package interview.guide.modules.training.service;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.training.model.TrainingEvidenceSnapshot;
import interview.guide.modules.training.model.TrainingTopicDiagnostic;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 将历史面试答案转换为固定的训练诊断快照。
 *
 * <p>该类是纯计算组件，不开启事务，也不调用 LLM 或其他外部服务。创建服务负责在一个短事务
 * 中查询历史答案、调用本工厂并保存结果。
 *
 * <p>弱项排序只使用数值平均分，平均分越低优先级越高。不会根据“较弱”“熟练”等展示文本
 * 排序，避免语言变化或字典序导致诊断顺序失真。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingDiagnosticSnapshotFactory {

  private static final int MAX_QUESTION_LENGTH = 2_000;
  private static final int MAX_ANSWER_LENGTH = 8_000;
  private static final int MAX_FEEDBACK_LENGTH = 4_000;
  private static final int MAX_REFERENCE_ANSWER_LENGTH = 8_000;
  private static final int MAX_KEY_POINT_LENGTH = 1_000;
  private static final int MAX_KEY_POINTS = 20;

  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
  };

  private final TrainingProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * 构建达到最小样本数的主题，并按数值平均分从低到高返回。
   *
   * @param answers 已按回答时间倒序加载的有限历史答案
   * @return 最多 {@code maxDiagnosticTopics} 个主题；没有合格主题时返回空列表
   */
  public List<TrainingTopicDiagnostic> build(List<InterviewAnswerEntity> answers) {
    Map<String, TopicAccumulator> grouped = new LinkedHashMap<>();
    for (InterviewAnswerEntity answer : answers) {
      if (!isValidEvidence(answer)) {
        continue;
      }

      String displayName = answer.getCategory().trim();
      String topicKey = normalizeTopicKey(displayName);
      grouped.computeIfAbsent(topicKey, ignored -> new TopicAccumulator(displayName))
          .add(answer);
    }

    return grouped.entrySet().stream()
        .filter(entry -> entry.getValue().sampleCount() >= properties.getMinimumEvidencePerTopic())
        .map(entry -> entry.getValue().toDiagnostic(
            entry.getKey(),
            properties.getMaxEvidencePerTopic()
        ))
        .sorted(Comparator
            .comparing(TrainingTopicDiagnostic::averageScore)
            .thenComparing(
                TrainingTopicDiagnostic::sampleCount,
                Comparator.reverseOrder()
            )
            .thenComparing(TrainingTopicDiagnostic::topicKey))
        .limit(properties.getMaxDiagnosticTopics())
        .toList();
  }

  private boolean isValidEvidence(InterviewAnswerEntity answer) {
    return answer != null
        && answer.getSession() != null
        && hasText(answer.getSession().getSessionId())
        && hasText(answer.getCategory())
        && hasText(answer.getQuestion())
        && hasText(answer.getUserAnswer())
        && answer.getScore() != null
        && answer.getScore() >= 0
        && answer.getScore() <= 100;
  }

  private String normalizeTopicKey(String displayName) {
    return displayName.trim().toLowerCase(Locale.ROOT);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private List<String> readKeyPoints(InterviewAnswerEntity answer) {
    if (!hasText(answer.getKeyPointsJson())) {
      return List.of();
    }
    try {
      List<String> parsed = objectMapper.readValue(answer.getKeyPointsJson(), STRING_LIST_TYPE);
      if (parsed == null) {
        return List.of();
      }
      return parsed.stream()
          .filter(this::hasText)
          .map(value -> truncate(value.trim(), MAX_KEY_POINT_LENGTH))
          .limit(MAX_KEY_POINTS)
          .toList();
    } catch (JacksonException e) {
      // 关键点是补充上下文，不应让一条旧脏数据阻断整场训练创建。
      log.warn("忽略无效历史关键点 JSON: answerId={}", answer.getId(), e);
      return List.of();
    }
  }

  private final class TopicAccumulator {

    private final String displayName;
    private final List<InterviewAnswerEntity> answers = new ArrayList<>();
    private int scoreSum;

    private TopicAccumulator(String displayName) {
      this.displayName = displayName;
    }

    private void add(InterviewAnswerEntity answer) {
      answers.add(answer);
      scoreSum += answer.getScore();
    }

    private int sampleCount() {
      return answers.size();
    }

    private TrainingTopicDiagnostic toDiagnostic(String topicKey, int maxEvidence) {
      BigDecimal averageScore = BigDecimal.valueOf(scoreSum)
          .divide(BigDecimal.valueOf(sampleCount()), 2, RoundingMode.HALF_UP);
      List<TrainingEvidenceSnapshot> evidence = answers.stream()
          .limit(maxEvidence)
          .map(answer -> new TrainingEvidenceSnapshot(
              answer.getId(),
              answer.getSession().getSessionId(),
              truncate(answer.getQuestion(), MAX_QUESTION_LENGTH),
              truncate(answer.getUserAnswer(), MAX_ANSWER_LENGTH),
              answer.getScore(),
              truncate(answer.getFeedback(), MAX_FEEDBACK_LENGTH),
              truncate(answer.getReferenceAnswer(), MAX_REFERENCE_ANSWER_LENGTH),
              readKeyPoints(answer),
              answer.getAnsweredAt()
          ))
          .toList();
      return new TrainingTopicDiagnostic(
          topicKey,
          displayName,
          averageScore,
          sampleCount(),
          evidence
      );
    }
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
