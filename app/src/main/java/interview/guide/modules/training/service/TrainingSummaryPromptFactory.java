package interview.guide.modules.training.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.training.model.TrainingSummaryContext;
import interview.guide.modules.training.model.TrainingSummaryContext.TurnSummary;
import interview.guide.modules.training.model.TrainingSummaryTopicResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 训练总结 Prompt 模板加载和安全数据包装。
 */
@Component
public class TrainingSummaryPromptFactory {

  private static final int MAX_TURN_EVIDENCE_CHARS = 20_000;
  private static final String SYSTEM_PATH =
      "classpath:prompts/training-summary-system.st";
  private static final String USER_PATH =
      "classpath:prompts/training-summary-user.st";

  private final PromptSanitizer promptSanitizer;
  private final PromptTemplate systemTemplate;
  private final PromptTemplate userTemplate;

  public TrainingSummaryPromptFactory(
      ResourceLoader resourceLoader,
      PromptSanitizer promptSanitizer
  ) throws IOException {
    this.promptSanitizer = promptSanitizer;
    this.systemTemplate = load(resourceLoader, SYSTEM_PATH);
    this.userTemplate = load(resourceLoader, USER_PATH);
  }

  public String buildSystemPrompt(String outputFormat) {
    return systemTemplate.render()
        + "\n\n"
        + outputFormat;
  }

  public String buildUserPrompt(TrainingSummaryContext context) {
    String evidence = context.turns().stream()
        .map(this::formatTurn)
        .collect(Collectors.joining("\n\n"));
    if (evidence.length() > MAX_TURN_EVIDENCE_CHARS) {
      evidence = evidence.substring(0, MAX_TURN_EVIDENCE_CHARS)
          + "\n...（总结证据已截断）";
    }
    String wrappedEvidence = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION
        + "\n"
        + promptSanitizer.wrapWithDelimiters("training-summary-evidence", evidence);
    return userTemplate.render(Map.of(
        "overallScore", context.overallScore(),
        "completedQuestionCount", context.completedQuestionCount(),
        "coveredTopicCount", context.coveredTopicCount(),
        "topicResults", formatTopics(context),
        "turnEvidence", wrappedEvidence
    ));
  }

  private String formatTopics(TrainingSummaryContext context) {
    return context.topics().stream()
        .map(this::formatTopic)
        .collect(Collectors.joining("\n"));
  }

  private String formatTopic(TrainingSummaryTopicResult topic) {
    return "- " + safe(topic.displayName())
        + " [" + topic.topicKey() + "]"
        + "：历史诊断均分=" + topic.originalAverageScore()
        + "，本次训练均分=" + topic.trainingAverageScore()
        + "，完成题数=" + topic.answeredQuestionCount();
  }

  private String formatTurn(TurnSummary turn) {
    return "轮次 " + turn.turnIndex()
        + " | 主题 " + turn.topicKey()
        + "\n问题: " + safe(turn.question())
        + "\n反馈: " + safe(turn.feedback());
  }

  private String safe(String value) {
    return value == null || value.isBlank()
        ? "无"
        : promptSanitizer.sanitize(value.trim());
  }

  private PromptTemplate load(ResourceLoader resourceLoader, String location)
      throws IOException {
    String content = resourceLoader.getResource(location)
        .getContentAsString(StandardCharsets.UTF_8);
    return new PromptTemplate(content);
  }
}
