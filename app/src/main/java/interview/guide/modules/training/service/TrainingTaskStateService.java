package interview.guide.modules.training.service;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.listener.TrainingTaskQueuedEvent;
import interview.guide.modules.training.model.TrainingSessionEntity;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingTaskDTO;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskStatus;
import interview.guide.modules.training.model.TrainingTaskType;
import interview.guide.modules.training.model.TrainingTurnEntity;
import interview.guide.modules.training.model.TrainingTurnStatus;
import interview.guide.modules.training.repository.TrainingSessionRepository;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import interview.guide.modules.training.repository.TrainingTurnRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 训练轮次异步任务的数据库状态机和事务边界。
 *
 * <p>这里不执行 LLM、Redis 或外部 HTTP 调用。写库完成后只发布应用内事件，Redis 投递由
 * AFTER_COMMIT 监听器完成。消费者同样通过本服务短事务推进状态，耗时的 ReAct 推理位于
 * 事务之外，避免长事务占用连接和锁。
 */
@Service
@RequiredArgsConstructor
public class TrainingTaskStateService {

  public static final String SAFE_TASK_FAILURE_MESSAGE = "训练处理失败，请稍后重试";
  public static final String SAFE_DISPATCH_FAILURE_MESSAGE =
      "任务暂未成功排队，系统将自动重试";
  private static final int ANSWER_MAX_LENGTH = 8_000;

  private final TrainingSessionRepository sessionRepository;
  private final TrainingTurnRepository turnRepository;
  private final TrainingTaskRepository taskRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final TrainingProperties properties;

  /**
   * 为新会话创建首题任务。deduplicationKey 使网络重试只返回原任务，不会生成两个首题。
   */
  @Transactional
  public TrainingTaskDTO createInitialTask(String trainingId) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    TrainingSessionEntity session = lockSession(normalizedTrainingId);
    String deduplicationKey = "initial:" + normalizedTrainingId;
    Optional<TrainingTaskEntity> existing = taskRepository.findByDeduplicationKey(
        deduplicationKey
    );
    if (existing.isPresent()) {
      return toDTO(existing.get());
    }
    if (session.getStatus() != TrainingSessionStatus.READY) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID);
    }

    LocalDateTime now = LocalDateTime.now();
    session.setStatus(TrainingSessionStatus.IN_PROGRESS);
    session.setStartedAt(now);
    TrainingTaskEntity task = TrainingTaskEntity.builder()
        .taskId(UUID.randomUUID().toString())
        .deduplicationKey(deduplicationKey)
        .session(session)
        .taskType(TrainingTaskType.INITIAL_TURN)
        .status(TrainingTaskStatus.QUEUED)
        .build();
    TrainingTaskEntity saved = taskRepository.save(task);
    publishAfterCommit(saved, 0);
    return toDTO(saved);
  }

  /**
   * 保存回答并创建分析任务。先保存回答、后投递消息，任何投递故障都不会要求用户重新输入。
   */
  @Transactional
  public TrainingTaskDTO queueAnswerTask(
      String trainingId,
      String turnId,
      String answer
  ) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    String normalizedTurnId = requireId(turnId, "训练轮次 ID 不能为空");
    String normalizedAnswer = requireAnswer(answer);
    TrainingSessionEntity session = lockSession(normalizedTrainingId);
    TrainingTurnEntity turn = turnRepository.findByTrainingIdAndTurnIdForUpdate(
        normalizedTrainingId,
        normalizedTurnId
    ).orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TURN_NOT_FOUND));

    String deduplicationKey = "answer:" + normalizedTurnId;
    Optional<TrainingTaskEntity> existing = taskRepository.findByDeduplicationKey(
        deduplicationKey
    );
    if (existing.isPresent()) {
      return toDTO(existing.get());
    }
    if (session.getStatus() != TrainingSessionStatus.IN_PROGRESS
        || turn.getStatus() != TrainingTurnStatus.WAITING_ANSWER) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID);
    }

    LocalDateTime now = LocalDateTime.now();
    turn.setUserAnswer(normalizedAnswer);
    turn.setAnsweredAt(now);
    turn.setStatus(TrainingTurnStatus.QUEUED);
    turn.setFailureMessage(null);
    TrainingTaskEntity task = TrainingTaskEntity.builder()
        .taskId(UUID.randomUUID().toString())
        .deduplicationKey(deduplicationKey)
        .session(session)
        .sourceTurn(turn)
        .taskType(TrainingTaskType.ANSWER_TURN)
        .status(TrainingTaskStatus.QUEUED)
        .build();
    TrainingTaskEntity saved = taskRepository.save(task);
    publishAfterCommit(saved, 0);
    return toDTO(saved);
  }

  @Transactional(readOnly = true)
  public TrainingTaskDTO getTask(String trainingId, String taskId) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    String normalizedTaskId = requireId(taskId, "训练任务 ID 不能为空");
    TrainingTaskEntity task = taskRepository.findByTaskId(normalizedTaskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED, "训练任务不存在"));
    if (!normalizedTrainingId.equals(task.getSession().getTrainingId())) {
      throw new BusinessException(ErrorCode.TRAINING_TASK_FAILED, "训练任务不存在");
    }
    return toDTO(task);
  }

  /**
   * Redis 消息可重复到达，只有数据库条件更新成功的消费者才能得到执行权。
   */
  @Transactional
  public boolean tryMarkProcessing(String taskId, String trainingId) {
    LocalDateTime now = LocalDateTime.now();
    int claimed = taskRepository.claimQueuedTask(
        taskId,
        trainingId,
        TrainingTaskStatus.QUEUED,
        TrainingTaskStatus.ANALYZING,
        now
    );
    if (claimed == 0) {
      return false;
    }

    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED));
    if (task.getSourceTurn() != null) {
      task.getSourceTurn().setStatus(TrainingTurnStatus.PROCESSING);
      task.getSourceTurn().setFailureMessage(null);
    }
    return true;
  }

  @Transactional(readOnly = true)
  public boolean shouldSkip(String taskId, String trainingId) {
    return taskRepository.findByTaskId(taskId)
        .map(task -> !trainingId.equals(task.getSession().getTrainingId())
            || task.getStatus() != TrainingTaskStatus.QUEUED)
        .orElse(true);
  }

  /**
   * ReAct Runner 在各阶段之间调用。允许跳过本轮不需要的阶段，但拒绝状态倒退和并发覆盖。
   */
  @Transactional
  public boolean advanceProgress(
      String taskId,
      TrainingTaskStatus expectedStatus,
      TrainingTaskStatus nextStatus
  ) {
    if (expectedStatus == null
        || nextStatus == null
        || !expectedStatus.isProcessing()
        || !nextStatus.isProcessing()
        || nextStatus.ordinal() <= expectedStatus.ordinal()) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID, "非法的任务进度变更");
    }
    return taskRepository.advanceStatus(
        taskId,
        expectedStatus,
        nextStatus,
        LocalDateTime.now()
    ) == 1;
  }

  @Transactional
  public boolean touchProcessing(String taskId) {
    return taskRepository.touchProcessing(
        taskId,
        TrainingTaskStatus.processingStatuses(),
        LocalDateTime.now()
    ) == 1;
  }

  @Transactional
  public boolean markCompleted(String taskId) {
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
    if (task != null && task.getStatus() == TrainingTaskStatus.COMPLETED) {
      return true;
    }
    if (task == null || !task.getStatus().isProcessing()) {
      return false;
    }
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(TrainingTaskStatus.COMPLETED);
    task.setSafeErrorMessage(null);
    task.setCompletedAt(now);
    if (task.getSourceTurn() != null
        && task.getSourceTurn().getStatus() == TrainingTurnStatus.PROCESSING) {
      task.getSourceTurn().setStatus(TrainingTurnStatus.COMPLETED);
      task.getSourceTurn().setFailureMessage(null);
      task.getSourceTurn().setCompletedAt(now);
    }
    return true;
  }

  @Transactional
  public boolean markFailed(String taskId) {
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
    if (task == null || !task.getStatus().isProcessing()) {
      return false;
    }
    failTask(task);
    return true;
  }

  /**
   * 消费失败后的自动重试只允许把正在处理的任务退回 QUEUED。
   */
  @Transactional
  public boolean resetForRetry(String taskId, int retryCount) {
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
    if (task == null
        || !task.getStatus().isProcessing()
        || retryCount < 1
        || retryCount > AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
      return false;
    }
    resetToQueued(task);
    task.setRetryCount(retryCount);
    return true;
  }

  /**
   * 用户手动重试最终失败的任务，保留原回答并重新触发 AFTER_COMMIT 投递。
   */
  @Transactional
  public TrainingTaskDTO retryFailedTask(String trainingId, String taskId) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    String normalizedTaskId = requireId(taskId, "训练任务 ID 不能为空");
    TrainingSessionEntity session = lockSession(normalizedTrainingId);
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(normalizedTaskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED, "训练任务不存在"));
    if (!normalizedTrainingId.equals(task.getSession().getTrainingId())
        || session.getStatus() != TrainingSessionStatus.IN_PROGRESS
        || task.getStatus() != TrainingTaskStatus.FAILED) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID);
    }
    resetToQueued(task);
    task.setRetryCount(0);
    publishAfterCommit(task, 0);
    return toDTO(task);
  }

  /**
   * Stream 发送失败只记录安全提示，状态继续保持 QUEUED，等待恢复调度再次投递。
   */
  @Transactional
  public void recordDispatchFailure(String taskId) {
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
    if (task != null && task.getStatus() == TrainingTaskStatus.QUEUED) {
      task.setSafeErrorMessage(SAFE_DISPATCH_FAILURE_MESSAGE);
    }
  }

  @Transactional(readOnly = true)
  public List<String> findStaleQueuedTaskIds(LocalDateTime threshold) {
    return taskRepository.findStaleTaskIds(
        List.of(TrainingTaskStatus.QUEUED),
        threshold,
        PageRequest.of(0, properties.getRecoveryBatchSize())
    );
  }

  @Transactional(readOnly = true)
  public List<String> findStaleProcessingTaskIds(LocalDateTime threshold) {
    return taskRepository.findStaleTaskIds(
        TrainingTaskStatus.processingStatuses(),
        threshold,
        PageRequest.of(0, properties.getRecoveryBatchSize())
    );
  }

  /**
   * 刷新等待任务的时间戳并返回投递事件；加锁后再次核对阈值，防止调度器重复恢复。
   */
  @Transactional
  public Optional<TrainingTaskQueuedEvent> prepareQueuedRecovery(
      String taskId,
      LocalDateTime threshold
  ) {
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
    if (task == null
        || task.getStatus() != TrainingTaskStatus.QUEUED
        || !isStale(task.getUpdatedAt(), threshold)) {
      return Optional.empty();
    }
    task.setSafeErrorMessage(null);
    task.setUpdatedAt(LocalDateTime.now());
    return Optional.of(toEvent(task, task.getRetryCount()));
  }

  /**
   * 处理中任务失去心跳后退回 QUEUED。原回答保留，新的消费者会从数据库重建上下文。
   */
  @Transactional
  public Optional<TrainingTaskQueuedEvent> prepareProcessingRecovery(
      String taskId,
      LocalDateTime threshold
  ) {
    TrainingTaskEntity task = taskRepository.findByTaskIdForUpdate(taskId).orElse(null);
    if (task == null
        || !task.getStatus().isProcessing()
        || !isStale(task.getUpdatedAt(), threshold)) {
      return Optional.empty();
    }
    if (task.getRetryCount() >= AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
      failTask(task);
      return Optional.empty();
    }
    int nextRetryCount = task.getRetryCount() + 1;
    resetToQueued(task);
    task.setRetryCount(nextRetryCount);
    return Optional.of(toEvent(task, nextRetryCount));
  }

  private void resetToQueued(TrainingTaskEntity task) {
    task.setStatus(TrainingTaskStatus.QUEUED);
    task.setSafeErrorMessage(null);
    task.setProcessingStartedAt(null);
    task.setCompletedAt(null);
    if (task.getSourceTurn() != null) {
      task.getSourceTurn().setStatus(TrainingTurnStatus.QUEUED);
      task.getSourceTurn().setFailureMessage(null);
      task.getSourceTurn().setCompletedAt(null);
    }
  }

  private void failTask(TrainingTaskEntity task) {
    task.setStatus(TrainingTaskStatus.FAILED);
    task.setSafeErrorMessage(SAFE_TASK_FAILURE_MESSAGE);
    task.setCompletedAt(LocalDateTime.now());
    if (task.getSourceTurn() != null) {
      task.getSourceTurn().setStatus(TrainingTurnStatus.FAILED);
      task.getSourceTurn().setFailureMessage(SAFE_TASK_FAILURE_MESSAGE);
    }
  }

  private TrainingSessionEntity lockSession(String trainingId) {
    return sessionRepository.findByTrainingIdForUpdate(trainingId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND));
  }

  private void publishAfterCommit(TrainingTaskEntity task, int retryCount) {
    eventPublisher.publishEvent(toEvent(task, retryCount));
  }

  private TrainingTaskQueuedEvent toEvent(TrainingTaskEntity task, int retryCount) {
    return new TrainingTaskQueuedEvent(
        task.getTaskId(),
        task.getSession().getTrainingId(),
        retryCount
    );
  }

  private TrainingTaskDTO toDTO(TrainingTaskEntity task) {
    return new TrainingTaskDTO(
        task.getTaskId(),
        task.getSession().getTrainingId(),
        task.getSourceTurn() == null ? null : task.getSourceTurn().getTurnId(),
        task.getTaskType(),
        task.getStatus(),
        task.getAttemptCount(),
        task.getSafeErrorMessage(),
        task.getCreatedAt(),
        task.getUpdatedAt(),
        task.getCompletedAt()
    );
  }

  private String requireId(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
    String normalized = value.trim();
    if (normalized.length() > 36) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "ID 长度不能超过 36 个字符");
    }
    return normalized;
  }

  private String requireAnswer(String answer) {
    if (answer == null || answer.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "回答不能为空");
    }
    String normalized = answer.trim();
    if (normalized.length() > ANSWER_MAX_LENGTH) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "回答长度不能超过 " + ANSWER_MAX_LENGTH + " 个字符"
      );
    }
    return normalized;
  }

  private boolean isStale(LocalDateTime updatedAt, LocalDateTime threshold) {
    return updatedAt == null || updatedAt.isBefore(threshold);
  }
}
