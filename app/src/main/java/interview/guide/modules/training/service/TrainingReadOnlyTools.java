package interview.guide.modules.training.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.training.model.TrainingEvidenceSnapshot;
import interview.guide.modules.training.model.TrainingExecutionContext;
import interview.guide.modules.training.model.TrainingExecutionContext.TopicSnapshot;
import interview.guide.modules.training.model.TrainingExecutionContext.TurnSnapshot;
import interview.guide.modules.training.model.TrainingTopicStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 单个训练任务专属的四个只读工具。
 *
 * <p>对象只持有创建时传入的不可变执行快照，不接受 trainingId、turnId 或任意数据库主键。
 * 即使模型伪造参数，也只能读取当前任务已经授权的数据，且所有输出都有条数和字符上限。
 */
public final class TrainingReadOnlyTools {

  private static final int MAX_TOOL_RESULT_CHARS = 4_000;
  private static final int MAX_EVIDENCE_ITEMS = 5;
  private static final int MAX_PREVIOUS_TURNS = 8;
  private static final int MAX_TOTAL_TOOL_CALLS = 6;
  private static final Pattern QUERY_SPLIT_PATTERN = Pattern.compile("[\\s,，。；;、:：]+");

  private final TrainingExecutionContext context;
  private final String skillReference;
  private final PromptSanitizer promptSanitizer;
  private final List<String> observations = new ArrayList<>();

  public TrainingReadOnlyTools(
      TrainingExecutionContext context,
      String skillReference,
      PromptSanitizer promptSanitizer
  ) {
    this.context = context;
    this.skillReference = skillReference == null ? "" : skillReference;
    this.promptSanitizer = promptSanitizer;
  }

  @Tool(
      name = "searchSkillReference",
      description = "在当前训练方向的本地参考资料中搜索知识点，只能读取当前方向"
  )
  public String searchSkillReference(
      @ToolParam(description = "要搜索的技术概念或关键词") String query
  ) {
    if (!hasToolBudget()) {
      return toolBudgetExhausted();
    }
    Set<String> terms = normalizeTerms(query);
    List<String> sections = splitSections(skillReference);
    String result = sections.stream()
        .filter(section -> terms.isEmpty() || matchesAny(section, terms))
        .limit(6)
        .reduce((left, right) -> left + "\n\n" + right)
        .orElse("未找到匹配的本地参考资料。");
    return recordObservation("searchSkillReference", limit(result));
  }

  @Tool(
      name = "getHistoricalEvidence",
      description = "读取当前训练某个弱项主题的有限历史问答与评估快照"
  )
  public String getHistoricalEvidence(
      @ToolParam(description = "弱项主题 key，必须来自当前训练主题列表") String topicKey
  ) {
    if (!hasToolBudget()) {
      return toolBudgetExhausted();
    }
    TopicSnapshot topic = findTopic(topicKey);
    if (topic == null) {
      return recordObservation("getHistoricalEvidence", "主题不属于当前训练，拒绝读取。");
    }

    StringBuilder result = new StringBuilder()
        .append("主题: ").append(safe(topic.displayName()))
        .append(" (").append(topic.topicKey()).append(")\n")
        .append("历史平均分: ").append(topic.originalAverageScore())
        .append("，有效样本: ").append(topic.sampleCount()).append('\n');
    topic.evidence().stream()
        .limit(MAX_EVIDENCE_ITEMS)
        .forEach(evidence -> appendEvidence(result, evidence));
    return recordObservation("getHistoricalEvidence", limit(result.toString()));
  }

  @Tool(
      name = "getPreviousTrainingTurns",
      description = "读取当前训练中此前已发生的有限轮次，不包含其他训练会话"
  )
  public String getPreviousTrainingTurns(
      @ToolParam(description = "需要返回的最近轮次数，范围 1 到 8") Integer limit
  ) {
    if (!hasToolBudget()) {
      return toolBudgetExhausted();
    }
    int safeLimit = limit == null ? 4 : Math.max(1, Math.min(limit, MAX_PREVIOUS_TURNS));
    List<TurnSnapshot> turns = context.previousTurns();
    int start = Math.max(0, turns.size() - safeLimit);
    if (start == turns.size()) {
      return recordObservation("getPreviousTrainingTurns", "当前训练还没有更早轮次。");
    }

    StringBuilder result = new StringBuilder();
    turns.subList(start, turns.size()).forEach(turn -> result
        .append("轮次 ").append(turn.turnIndex())
        .append(" | 主题 ").append(turn.topicKey())
        .append(" | 动作 ").append(turn.action()).append('\n')
        .append("问题: ").append(safe(turn.question())).append('\n')
        .append("回答: ").append(safe(turn.userAnswer())).append('\n')
        .append("反馈: ").append(safe(turn.feedback())).append("\n\n"));
    return recordObservation("getPreviousTrainingTurns", limit(result.toString()));
  }

  @Tool(
      name = "getRemainingWeakTopics",
      description = "查看当前训练尚未完成的弱项主题及硬约束计数"
  )
  public String getRemainingWeakTopics() {
    if (!hasToolBudget()) {
      return toolBudgetExhausted();
    }
    StringBuilder result = new StringBuilder()
        .append("总题数: ").append(context.questionCount())
        .append('/').append(context.maxQuestions())
        .append("，已覆盖主题: ").append(context.coveredTopicCount())
        .append('/').append(context.minimumTopicCount()).append('\n');
    context.topics().stream()
        .filter(topic -> topic.status() != TrainingTopicStatus.COMPLETED)
        .sorted(Comparator.comparingInt(TopicSnapshot::priorityRank))
        .forEach(topic -> result
            .append("- ").append(safe(topic.displayName()))
            .append(" [").append(topic.topicKey()).append(']')
            .append("，状态=").append(topic.status())
            .append("，已出题=").append(topic.questionCount())
            .append("，原平均分=").append(topic.originalAverageScore())
            .append('\n'));
    return recordObservation("getRemainingWeakTopics", limit(result.toString()));
  }

  /**
   * 供最终结构化决策 Prompt 使用。只返回工具名称和已经清洗、截断的结果，不记录参数。
   */
  public List<String> observations() {
    return List.copyOf(observations);
  }

  private TopicSnapshot findTopic(String topicKey) {
    if (topicKey == null || topicKey.isBlank()) {
      return null;
    }
    String normalized = topicKey.trim().toLowerCase(Locale.ROOT);
    return context.topics().stream()
        .filter(topic -> topic.topicKey().equals(normalized))
        .findFirst()
        .orElse(null);
  }

  private void appendEvidence(StringBuilder result, TrainingEvidenceSnapshot evidence) {
    result.append("\n问题: ").append(safe(evidence.question()))
        .append("\n历史回答: ").append(safe(evidence.userAnswer()))
        .append("\n得分: ").append(evidence.score())
        .append("\n历史反馈: ").append(safe(evidence.feedback()))
        .append("\n参考答案: ").append(safe(evidence.referenceAnswer()))
        .append('\n');
  }

  private Set<String> normalizeTerms(String query) {
    if (query == null || query.isBlank()) {
      return Set.of();
    }
    Set<String> terms = new LinkedHashSet<>();
    for (String term : QUERY_SPLIT_PATTERN.split(query.toLowerCase(Locale.ROOT))) {
      if (term.length() >= 2) {
        terms.add(term);
      }
    }
    return terms;
  }

  private List<String> splitSections(String reference) {
    if (reference == null || reference.isBlank()) {
      return List.of();
    }
    return List.of(reference.split("(?m)(?=^#{1,3}\\s)"));
  }

  private boolean matchesAny(String section, Set<String> terms) {
    String normalized = section.toLowerCase(Locale.ROOT);
    return terms.stream().anyMatch(normalized::contains);
  }

  private String recordObservation(String toolName, String result) {
    /*
     * 技能参考资料也可能来自可编辑文件，不能因为它不是用户本轮回答就默认可信。
     * 在写入观察列表和返回模型前统一清洗并使用随机边界包装，保证四个工具走同一条安全出口。
     *
     * 工具返回值会先进入下一轮推理的 ToolResponseMessage，而不只是最终决策 Prompt。
     * 因此必须在这里建立边界，不能只依赖 TrainingPromptFactory 对最终 observation 汇总的包装。
     */
    String sanitized = promptSanitizer.sanitize(result);
    String wrapped = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION
        + "\n"
        + promptSanitizer.wrapWithDelimiters(
            "training-tool-" + toolName,
            sanitized
        );
    observations.add(toolName + ":\n" + wrapped);
    return wrapped;
  }

  private boolean hasToolBudget() {
    return observations.size() < MAX_TOTAL_TOOL_CALLS;
  }

  private String toolBudgetExhausted() {
    return "本轮只读工具调用预算已用完，请基于已有证据完成决定。";
  }

  private String safe(String value) {
    if (value == null || value.isBlank()) {
      return "无";
    }
    return promptSanitizer.sanitize(value.trim());
  }

  private String limit(String value) {
    if (value.length() <= MAX_TOOL_RESULT_CHARS) {
      return value;
    }
    return value.substring(0, MAX_TOOL_RESULT_CHARS) + "\n...（工具结果已截断）";
  }
}
