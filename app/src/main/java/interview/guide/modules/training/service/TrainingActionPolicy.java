package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingAction;
import interview.guide.modules.training.model.TrainingActionState;
import interview.guide.modules.training.model.TrainingActionState.TopicState;
import interview.guide.modules.training.model.TrainingDecisionCandidate;
import interview.guide.modules.training.model.TrainingExecutionContext;
import interview.guide.modules.training.model.TrainingResolvedDecision;
import interview.guide.modules.training.model.TrainingTopicStatus;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 服务端训练动作策略。
 *
 * <p>模型只能在 allowedActions 中选择，主题也只能来自固定诊断快照。任何未知动作、越界
 * 主题、缺失文本或非法分数都会被确定性修正，不会直接执行，也不会为了格式小错反复失败。
 */
@Component
public class TrainingActionPolicy {

  private static final int MAX_FEEDBACK_CHARS = 2_000;
  private static final int MAX_REFERENCE_ANSWER_CHARS = 4_000;
  private static final int MAX_QUESTION_CHARS = 1_000;

  public TrainingActionState from(TrainingExecutionContext context) {
    return new TrainingActionState(
        context.isInitialTask(),
        context.maxQuestions(),
        context.maxConsecutiveQuestionsPerTopic(),
        context.maxFollowUpsPerMainQuestion(),
        context.minimumTopicCount(),
        context.questionCount(),
        context.coveredTopicCount(),
        context.currentTopicKey(),
        context.currentMainQuestionIndex(),
        context.consecutiveTopicQuestionCount(),
        context.currentMainQuestionFollowUpCount(),
        context.sourceTurn() == null ? null : context.sourceTurn().topicKey(),
        context.topics().stream()
            .map(topic -> new TopicState(
                topic.topicKey(),
                topic.displayName(),
                topic.priorityRank(),
                topic.status()
            ))
            .toList()
    );
  }

  public Set<TrainingAction> allowedActions(TrainingActionState state) {
    LinkedHashSet<TrainingAction> allowed = new LinkedHashSet<>();
    if (state.initialTask()) {
      allowed.add(TrainingAction.ASK_NEW_QUESTION);
      return Set.copyOf(allowed);
    }
    if (state.questionCount() >= state.maxQuestions()) {
      allowed.add(TrainingAction.FINISH);
      return Set.copyOf(allowed);
    }

    boolean hasOtherTopic = findSwitchTopic(state, null) != null;
    if (state.consecutiveTopicQuestionCount()
        >= state.maxConsecutiveQuestionsPerTopic()) {
      allowed.add(hasOtherTopic ? TrainingAction.SWITCH_TOPIC : TrainingAction.FINISH);
      return Set.copyOf(allowed);
    }

    if (state.currentMainQuestionFollowUpCount()
        < state.maxFollowUpsPerMainQuestion()) {
      allowed.add(TrainingAction.FOLLOW_UP);
    }
    allowed.add(TrainingAction.REINFORCE);
    allowed.add(TrainingAction.ASK_NEW_QUESTION);
    if (hasOtherTopic) {
      allowed.add(TrainingAction.SWITCH_TOPIC);
    }
    if (state.coveredTopicCount() >= state.minimumTopicCount()) {
      allowed.add(TrainingAction.FINISH);
    }
    return Set.copyOf(allowed);
  }

  public TrainingResolvedDecision resolve(
      TrainingActionState state,
      TrainingDecisionCandidate candidate
  ) {
    Set<TrainingAction> allowed = allowedActions(state);
    TrainingAction requested = parseAction(candidate == null ? null : candidate.action());
    Integer requestedScore = candidate == null ? null : candidate.score();
    int score = normalizeScore(requestedScore);
    TrainingAction action = requested != null && allowed.contains(requested)
        ? requested
        : chooseFallbackAction(allowed, score);
    boolean fallback = requested != action
        || requestedScore != null && requestedScore != score
        || !state.initialTask() && requestedScore == null;

    String targetTopicKey = resolveTargetTopic(
        state,
        action,
        candidate == null ? null : candidate.targetTopicKey()
    );
    String requestedTopic = normalizeTopicKey(
        candidate == null ? null : candidate.targetTopicKey()
    );
    if (action != TrainingAction.FINISH
        && !targetTopicKey.equals(requestedTopic)) {
      fallback = true;
    }

    String feedback = normalizeText(
        candidate == null ? null : candidate.feedback(),
        MAX_FEEDBACK_CHARS
    );
    String referenceAnswer = normalizeText(
        candidate == null ? null : candidate.referenceAnswer(),
        MAX_REFERENCE_ANSWER_CHARS
    );
    String nextQuestion = normalizeText(
        candidate == null ? null : candidate.nextQuestion(),
        MAX_QUESTION_CHARS
    );
    if (!state.initialTask() && feedback == null) {
      feedback = "回答已记录。建议补充核心原理、适用边界以及具体实践依据。";
      fallback = true;
    }
    if (!state.initialTask() && referenceAnswer == null) {
      referenceAnswer = "请围绕核心概念、工作原理、适用场景和关键权衡进行完整说明。";
      fallback = true;
    }
    if (action != TrainingAction.FINISH && nextQuestion == null) {
      nextQuestion = buildFallbackQuestion(state, targetTopicKey, action);
      fallback = true;
    }
    if (action == TrainingAction.FINISH) {
      if (requestedTopic != null || nextQuestion != null) {
        fallback = true;
      }
      targetTopicKey = null;
      nextQuestion = null;
    }

    return new TrainingResolvedDecision(
        action,
        targetTopicKey,
        score,
        feedback,
        referenceAnswer,
        nextQuestion,
        fallback
    );
  }

  private TrainingAction chooseFallbackAction(Set<TrainingAction> allowed, int score) {
    if (allowed.size() == 1) {
      return allowed.iterator().next();
    }
    if (score < 60 && allowed.contains(TrainingAction.FOLLOW_UP)) {
      return TrainingAction.FOLLOW_UP;
    }
    if (score < 75 && allowed.contains(TrainingAction.REINFORCE)) {
      return TrainingAction.REINFORCE;
    }
    if (allowed.contains(TrainingAction.ASK_NEW_QUESTION)) {
      return TrainingAction.ASK_NEW_QUESTION;
    }
    if (allowed.contains(TrainingAction.SWITCH_TOPIC)) {
      return TrainingAction.SWITCH_TOPIC;
    }
    return TrainingAction.FINISH;
  }

  private String resolveTargetTopic(
      TrainingActionState state,
      TrainingAction action,
      String requestedTopicKey
  ) {
    if (action == TrainingAction.FINISH) {
      return null;
    }
    if (state.initialTask()) {
      TopicState initial = state.topics().stream()
          .filter(topic -> topic.status() == TrainingTopicStatus.PENDING)
          .min(Comparator.comparingInt(TopicState::priorityRank))
          .orElseGet(() -> state.topics().stream()
              .min(Comparator.comparingInt(TopicState::priorityRank))
              .orElseThrow(() -> new BusinessException(
                  ErrorCode.TRAINING_SNAPSHOT_FAILED,
                  "训练主题快照为空"
              )));
      return initial.topicKey();
    }
    if (action != TrainingAction.SWITCH_TOPIC) {
      return state.sourceTopicKey() != null
          ? state.sourceTopicKey()
          : state.currentTopicKey();
    }
    TopicState switchTopic = findSwitchTopic(state, normalizeTopicKey(requestedTopicKey));
    return switchTopic == null ? state.currentTopicKey() : switchTopic.topicKey();
  }

  private TopicState findSwitchTopic(TrainingActionState state, String requestedTopicKey) {
    List<TopicState> candidates = state.topics().stream()
        .filter(topic -> topic.status() != TrainingTopicStatus.COMPLETED)
        .filter(topic -> !topic.topicKey().equals(state.currentTopicKey()))
        .sorted(Comparator
            .comparing((TopicState topic) -> topic.status() != TrainingTopicStatus.PENDING)
            .thenComparingInt(TopicState::priorityRank))
        .toList();
    if (requestedTopicKey != null) {
      TopicState requested = candidates.stream()
          .filter(topic -> topic.topicKey().equals(requestedTopicKey))
          .findFirst()
          .orElse(null);
      if (requested != null) {
        return requested;
      }
    }
    return candidates.isEmpty() ? null : candidates.getFirst();
  }

  private String buildFallbackQuestion(
      TrainingActionState state,
      String topicKey,
      TrainingAction action
  ) {
    String displayName = state.topics().stream()
        .filter(topic -> topic.topicKey().equals(topicKey))
        .map(TopicState::displayName)
        .findFirst()
        .orElse(topicKey);
    if (action == TrainingAction.FOLLOW_UP) {
      return "请进一步说明你刚才回答中的关键依据、适用边界和可能的反例。";
    }
    return "请结合实际项目，系统说明你对“"
        + displayName
        + "”的理解、核心机制和常见权衡。";
  }

  private TrainingAction parseAction(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return TrainingAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private String normalizeTopicKey(String value) {
    return value == null || value.isBlank()
        ? null
        : value.trim().toLowerCase(Locale.ROOT);
  }

  private int normalizeScore(Integer score) {
    return score == null ? 0 : Math.max(0, Math.min(score, 100));
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
