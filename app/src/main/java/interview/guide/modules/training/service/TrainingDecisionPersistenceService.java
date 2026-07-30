package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingAction;
import interview.guide.modules.training.model.TrainingActionState;
import interview.guide.modules.training.model.TrainingActionState.TopicState;
import interview.guide.modules.training.model.TrainingDecisionCandidate;
import interview.guide.modules.training.model.TrainingResolvedDecision;
import interview.guide.modules.training.model.TrainingSessionEntity;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskStatus;
import interview.guide.modules.training.model.TrainingTaskType;
import interview.guide.modules.training.model.TrainingTopicEntity;
import interview.guide.modules.training.model.TrainingTopicStatus;
import interview.guide.modules.training.model.TrainingTurnEntity;
import interview.guide.modules.training.model.TrainingTurnStatus;
import interview.guide.modules.training.repository.TrainingSessionRepository;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import interview.guide.modules.training.repository.TrainingTopicRepository;
import interview.guide.modules.training.repository.TrainingTurnRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将已解析的训练决定在一个短事务中重新校验并落库。
 *
 * <p>LLM 调用已经在事务外完成。这里按 session -> task -> turn/topic 的固定顺序加锁，
 * 使用锁内最新状态重算 allowedActions，防止恢复任务或并发请求执行过期决定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingDecisionPersistenceService {

  private final TrainingSessionRepository sessionRepository;
  private final TrainingTaskRepository taskRepository;
  private final TrainingTopicRepository topicRepository;
  private final TrainingTurnRepository turnRepository;
  private final TrainingActionPolicy actionPolicy;

  @Transactional
  public TrainingResolvedDecision apply(
      String taskId,
      TrainingResolvedDecision proposed
  ) {
    TrainingTaskEntity taskSnapshot = taskRepository.findByTaskId(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED));
    String trainingId = taskSnapshot.getSession().getTrainingId();
    TrainingSessionEntity session = sessionRepository.findByTrainingIdForUpdate(trainingId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND));
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED));
    if (task.getStatus() != TrainingTaskStatus.GENERATING
        || session.getStatus() != TrainingSessionStatus.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID);
    }

    TrainingTurnEntity sourceTurn = lockSourceTurn(task, trainingId);
    List<TrainingTopicEntity> topics = topicRepository.findByTrainingIdForUpdate(trainingId);
    TrainingActionState actionState = buildActionState(task, session, sourceTurn, topics);
    TrainingResolvedDecision resolved = actionPolicy.resolve(
        actionState,
        toCandidate(proposed)
    );
    if (proposed.fallbackApplied() && !resolved.fallbackApplied()) {
      resolved = new TrainingResolvedDecision(
          resolved.action(),
          resolved.targetTopicKey(),
          resolved.score(),
          resolved.feedback(),
          resolved.referenceAnswer(),
          resolved.nextQuestion(),
          true
      );
    }

    if (sourceTurn != null) {
      sourceTurn.setFeedback(resolved.feedback());
      sourceTurn.setInternalScore(resolved.score());
      sourceTurn.setReferenceAnswer(resolved.referenceAnswer());
    }
    if (resolved.action() == TrainingAction.FINISH) {
      topics.stream()
          .filter(topic -> topic.getStatus() == TrainingTopicStatus.ACTIVE)
          .forEach(topic -> topic.setStatus(TrainingTopicStatus.COMPLETED));
      session.setStatus(TrainingSessionStatus.SUMMARIZING);
      completeTask(task, sourceTurn);
      log.info("训练轮次决定结束会话: trainingId={}, taskId={}", trainingId, taskId);
      return resolved;
    }

    TrainingTopicEntity targetTopic = topics.stream()
        .filter(topic -> topic.getTopicKey().equals(resolved.targetTopicKey()))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_ACTION_INVALID));
    createNextTurn(session, sourceTurn, targetTopic, topics, resolved);
    completeTask(task, sourceTurn);
    log.info(
        "训练轮次决定已落库: trainingId={}, taskId={}, action={}, topic={}, fallback={}",
        trainingId,
        taskId,
        resolved.action(),
        resolved.targetTopicKey(),
        resolved.fallbackApplied()
    );
    return resolved;
  }

  private TrainingTurnEntity lockSourceTurn(TrainingTaskEntity task, String trainingId) {
    if (task.getTaskType() == TrainingTaskType.INITIAL_TURN) {
      return null;
    }
    if (task.getSourceTurn() == null) {
      throw new BusinessException(ErrorCode.TRAINING_TURN_NOT_FOUND);
    }
    TrainingTurnEntity source = turnRepository.findByTrainingIdAndTurnIdForUpdate(
        trainingId,
        task.getSourceTurn().getTurnId()
    ).orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TURN_NOT_FOUND));
    if (source.getStatus() != TrainingTurnStatus.PROCESSING) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID);
    }
    return source;
  }

  private TrainingActionState buildActionState(
      TrainingTaskEntity task,
      TrainingSessionEntity session,
      TrainingTurnEntity sourceTurn,
      List<TrainingTopicEntity> topics
  ) {
    return new TrainingActionState(
        task.getTaskType() == TrainingTaskType.INITIAL_TURN,
        session.getMaxQuestions(),
        session.getMaxConsecutiveQuestionsPerTopic(),
        session.getMaxFollowUpsPerMainQuestion(),
        session.getMinimumTopicCount(),
        session.getQuestionCount(),
        session.getCoveredTopicCount(),
        session.getCurrentTopicKey(),
        session.getCurrentMainQuestionIndex(),
        session.getConsecutiveTopicQuestionCount(),
        session.getCurrentMainQuestionFollowUpCount(),
        sourceTurn == null ? null : sourceTurn.getTopicKey(),
        topics.stream()
            .map(topic -> new TopicState(
                topic.getTopicKey(),
                topic.getDisplayName(),
                topic.getPriorityRank(),
                topic.getStatus()
            ))
            .toList()
    );
  }

  private TrainingDecisionCandidate toCandidate(TrainingResolvedDecision proposed) {
    return new TrainingDecisionCandidate(
        proposed.action().name(),
        proposed.targetTopicKey(),
        proposed.score(),
        proposed.feedback(),
        proposed.referenceAnswer(),
        proposed.nextQuestion()
    );
  }

  private void createNextTurn(
      TrainingSessionEntity session,
      TrainingTurnEntity sourceTurn,
      TrainingTopicEntity targetTopic,
      List<TrainingTopicEntity> topics,
      TrainingResolvedDecision decision
  ) {
    boolean topicChanged = !targetTopic.getTopicKey().equals(session.getCurrentTopicKey());
    if (topicChanged) {
      topics.stream()
          .filter(topic -> topic.getStatus() == TrainingTopicStatus.ACTIVE)
          .forEach(topic -> topic.setStatus(TrainingTopicStatus.COMPLETED));
      if (targetTopic.getStatus() == TrainingTopicStatus.PENDING) {
        session.setCoveredTopicCount(session.getCoveredTopicCount() + 1);
      }
      targetTopic.setStatus(TrainingTopicStatus.ACTIVE);
      session.setConsecutiveTopicQuestionCount(1);
    } else {
      session.setConsecutiveTopicQuestionCount(
          session.getConsecutiveTopicQuestionCount() + 1
      );
    }

    boolean followUp = decision.action() == TrainingAction.FOLLOW_UP;
    int mainQuestionIndex = followUp && session.getCurrentMainQuestionIndex() != null
        ? session.getCurrentMainQuestionIndex()
        : session.getCurrentMainQuestionIndex() == null
            ? 1
            : session.getCurrentMainQuestionIndex() + 1;
    int turnIndex = session.getQuestionCount() + 1;
    TrainingTurnEntity nextTurn = TrainingTurnEntity.builder()
        .turnId(UUID.randomUUID().toString())
        .session(session)
        .turnIndex(turnIndex)
        .mainQuestionIndex(mainQuestionIndex)
        .parentTurn(followUp ? sourceTurn : null)
        .action(decision.action())
        .topicKey(targetTopic.getTopicKey())
        .question(decision.nextQuestion())
        .status(TrainingTurnStatus.WAITING_ANSWER)
        .build();
    turnRepository.save(nextTurn);

    session.setQuestionCount(turnIndex);
    session.setCurrentTopicKey(targetTopic.getTopicKey());
    session.setCurrentMainQuestionIndex(mainQuestionIndex);
    session.setCurrentMainQuestionFollowUpCount(
        followUp ? session.getCurrentMainQuestionFollowUpCount() + 1 : 0
    );
    targetTopic.setQuestionCount(targetTopic.getQuestionCount() + 1);
  }

  /**
   * 与下一题/结束状态在同一事务提交，消除业务结果已写入但任务仍可被恢复重放的窗口。
   */
  private void completeTask(
      TrainingTaskEntity task,
      TrainingTurnEntity sourceTurn
  ) {
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(TrainingTaskStatus.COMPLETED);
    task.setSafeErrorMessage(null);
    task.setCompletedAt(now);
    if (sourceTurn != null) {
      sourceTurn.setStatus(TrainingTurnStatus.COMPLETED);
      sourceTurn.setFailureMessage(null);
      sourceTurn.setCompletedAt(now);
    }
  }
}
