package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingSessionEntity;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingSummaryContent;
import interview.guide.modules.training.model.TrainingSummaryContext;
import interview.guide.modules.training.model.TrainingSummaryEntity;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskStatus;
import interview.guide.modules.training.model.TrainingTaskType;
import interview.guide.modules.training.repository.TrainingSessionRepository;
import interview.guide.modules.training.repository.TrainingSummaryRepository;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 在短事务中保存总结快照并结束训练会话。
 *
 * <p>LLM 已在事务外完成。这里重新按 session -> task 的顺序加写锁，并验证统计快照仍与
 * 会话题目数量一致；总结、会话完成状态和任务完成状态随后原子提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingSummaryPersistenceService {

  private final TrainingSessionRepository sessionRepository;
  private final TrainingTaskRepository taskRepository;
  private final TrainingSummaryRepository summaryRepository;
  private final ObjectMapper objectMapper;

  @Transactional
  public void apply(
      String taskId,
      TrainingSummaryContext context,
      TrainingSummaryContent content
  ) {
    TrainingTaskEntity snapshot = taskRepository.findByTaskId(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SUMMARY_FAILED));
    String trainingId = snapshot.getSession().getTrainingId();
    TrainingSessionEntity session = sessionRepository.findByTrainingIdForUpdate(trainingId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND));
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SUMMARY_FAILED));
    validateState(task, session, context);
    if (summaryRepository.existsBySession_TrainingId(trainingId)) {
      throw new BusinessException(
          ErrorCode.TRAINING_SESSION_STATE_INVALID,
          "训练总结已经生成"
      );
    }

    TrainingSummaryEntity summary = TrainingSummaryEntity.builder()
        .session(session)
        .overallScore(context.overallScore())
        .completedQuestionCount(context.completedQuestionCount())
        .coveredTopicCount(context.coveredTopicCount())
        .narrative(content.narrative())
        .strengthsJson(writeJson(content.strengths()))
        .improvementsJson(writeJson(content.improvements()))
        .nextStepsJson(writeJson(content.nextSteps()))
        .topicResultsJson(writeJson(context.topics()))
        .build();
    summaryRepository.save(summary);

    LocalDateTime now = LocalDateTime.now();
    session.setStatus(TrainingSessionStatus.COMPLETED);
    session.setCompletedAt(now);
    task.setStatus(TrainingTaskStatus.COMPLETED);
    task.setSafeErrorMessage(null);
    task.setCompletedAt(now);
    log.info(
        "训练总结已原子落库: trainingId={}, taskId={}, overallScore={}, topics={}, fallback={}",
        trainingId,
        taskId,
        context.overallScore(),
        context.topics().size(),
        content.fallbackApplied()
    );
  }

  private void validateState(
      TrainingTaskEntity task,
      TrainingSessionEntity session,
      TrainingSummaryContext context
  ) {
    if (task.getTaskType() != TrainingTaskType.SUMMARY
        || task.getStatus() != TrainingTaskStatus.GENERATING
        || session.getStatus() != TrainingSessionStatus.SUMMARIZING
        || !task.getTaskId().equals(context.taskId())
        || !session.getTrainingId().equals(context.trainingId())
        || session.getQuestionCount() != context.completedQuestionCount()
        || session.getCoveredTopicCount() != context.coveredTopicCount()) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException e) {
      log.error("序列化训练总结失败", e);
      throw new BusinessException(
          ErrorCode.TRAINING_SUMMARY_FAILED,
          "训练总结无法保存"
      );
    }
  }
}
