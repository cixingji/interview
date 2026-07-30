package interview.guide.modules.training.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.training.model.TrainingAction;
import interview.guide.modules.training.model.TrainingExecutionContext;
import interview.guide.modules.training.model.TrainingExecutionContext.TopicSnapshot;
import interview.guide.modules.training.model.TrainingExecutionContext.TurnSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 加载并渲染训练 Prompt 模板。
 *
 * <p>所有用户回答都先清洗并放入随机边界标签；system prompt 同时声明工具结果也是待分析
 * 数据，避免历史回答中的指令通过工具响应绕过 Prompt 注入边界。
 */
@Component
public class TrainingPromptFactory {

  private static final int MAX_OBSERVATION_CHARS = 12_000;
  private static final String REASONING_SYSTEM_PATH =
      "classpath:prompts/training-react-reasoning-system.st";
  private static final String REASONING_USER_PATH =
      "classpath:prompts/training-react-reasoning-user.st";
  private static final String DECISION_SYSTEM_PATH =
      "classpath:prompts/training-react-decision-system.st";
  private static final String DECISION_USER_PATH =
      "classpath:prompts/training-react-decision-user.st";

  private final PromptSanitizer promptSanitizer;
  private final PromptTemplate reasoningSystemTemplate;
  private final PromptTemplate reasoningUserTemplate;
  private final PromptTemplate decisionSystemTemplate;
  private final PromptTemplate decisionUserTemplate;

  public TrainingPromptFactory(
      ResourceLoader resourceLoader,
      PromptSanitizer promptSanitizer
  ) throws IOException {
    this.promptSanitizer = promptSanitizer;
    this.reasoningSystemTemplate = load(resourceLoader, REASONING_SYSTEM_PATH);
    this.reasoningUserTemplate = load(resourceLoader, REASONING_USER_PATH);
    this.decisionSystemTemplate = load(resourceLoader, DECISION_SYSTEM_PATH);
    this.decisionUserTemplate = load(resourceLoader, DECISION_USER_PATH);
  }

  public String buildReasoningSystemPrompt() {
    return reasoningSystemTemplate.render()
        + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
  }

  public String buildReasoningUserPrompt(
      TrainingExecutionContext context,
      Set<TrainingAction> allowedActions
  ) {
    return reasoningUserTemplate.render(baseVariables(context, allowedActions));
  }

  public String buildDecisionSystemPrompt(String outputFormat) {
    return decisionSystemTemplate.render() + "\n\n" + outputFormat;
  }

  public String buildDecisionUserPrompt(
      TrainingExecutionContext context,
      Set<TrainingAction> allowedActions,
      List<String> observations
  ) {
    Map<String, Object> variables = baseVariables(context, allowedActions);
    variables.put(
        "toolObservations",
        observations.isEmpty()
            ? "本轮未调用工具，请仅基于可信会话状态决定。"
            : wrapToolObservations(
                limitObservations(String.join("\n\n", observations))
            )
    );
    return decisionUserTemplate.render(variables);
  }

  private Map<String, Object> baseVariables(
      TrainingExecutionContext context,
      Set<TrainingAction> allowedActions
  ) {
    Map<String, Object> variables = new java.util.HashMap<>();
    variables.put("taskType", context.taskType().name());
    variables.put("allowedActions", allowedActions.stream()
        .map(Enum::name)
        .sorted()
        .collect(Collectors.joining(", ")));
    variables.put("questionCount", context.questionCount());
    variables.put("maxQuestions", context.maxQuestions());
    variables.put("coveredTopicCount", context.coveredTopicCount());
    variables.put("minimumTopicCount", context.minimumTopicCount());
    variables.put(
        "consecutiveTopicQuestionCount",
        context.consecutiveTopicQuestionCount()
    );
    variables.put(
        "maxConsecutiveQuestionsPerTopic",
        context.maxConsecutiveQuestionsPerTopic()
    );
    variables.put(
        "currentMainQuestionFollowUpCount",
        context.currentMainQuestionFollowUpCount()
    );
    variables.put(
        "maxFollowUpsPerMainQuestion",
        context.maxFollowUpsPerMainQuestion()
    );
    variables.put(
        "currentTopicKey",
        wrapUntrustedData(
            "training-current-topic",
            safe(context.currentTopicKey())
        )
    );
    variables.put(
        "topics",
        wrapUntrustedData("training-topics", buildTopicSection(context.topics()))
    );
    variables.put("currentTurn", buildCurrentTurnSection(context.sourceTurn()));
    return variables;
  }

  private String buildTopicSection(List<TopicSnapshot> topics) {
    return topics.stream()
        .map(topic -> "- "
            + safe(topic.displayName())
            + " [" + safe(topic.topicKey()) + "]"
            + " status=" + topic.status()
            + " priority=" + topic.priorityRank()
            + " originalAverage=" + topic.originalAverageScore()
            + " questions=" + topic.questionCount())
        .collect(Collectors.joining("\n"));
  }

  private String buildCurrentTurnSection(TurnSnapshot turn) {
    if (turn == null) {
      return "这是首题任务，没有待评估回答。";
    }
    String content = "topic=" + safe(turn.topicKey())
        + "\nquestion=" + safe(turn.question())
        + "\nuserAnswer=" + safe(turn.userAnswer());
    return wrapUntrustedData("training-turn", content);
  }

  private String safe(String value) {
    return value == null || value.isBlank()
        ? "无"
        : promptSanitizer.sanitize(value.trim());
  }

  private String limitObservations(String observations) {
    return observations.length() <= MAX_OBSERVATION_CHARS
        ? observations
        : observations.substring(0, MAX_OBSERVATION_CHARS)
            + "\n...（工具观察总长度已截断）";
  }

  /**
   * 工具结果可能包含历史回答或可编辑参考资料。随机边界让系统提示中的“数据而非指令”
   * 规则有明确作用范围，也防止数据自行伪造固定结束标记。
   */
  private String wrapToolObservations(String observations) {
    return wrapUntrustedData("training-tool-observations", observations);
  }

  /**
   * 所有来自历史题目、用户回答或工具结果的可变文本都使用随机边界包装。
   *
   * <p>文本清洗用于移除已知注入模式，随机边界则提供独立的第二层语义隔离。即使运维临时关闭清洗器，
   * system prompt 仍能明确识别哪些内容只是待分析数据，避免历史分类名或回答伪装成可信任务指令。
   */
  private String wrapUntrustedData(String label, String content) {
    return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION
        + "\n"
        + promptSanitizer.wrapWithDelimiters(label, content);
  }

  private PromptTemplate load(ResourceLoader resourceLoader, String location)
      throws IOException {
    String content = resourceLoader.getResource(location)
        .getContentAsString(StandardCharsets.UTF_8);
    return new PromptTemplate(content);
  }
}
