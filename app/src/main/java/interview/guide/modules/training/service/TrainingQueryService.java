package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskPollResponse;
import interview.guide.modules.training.model.TrainingTaskStatus;
import interview.guide.modules.training.model.TrainingTaskType;
import interview.guide.modules.training.model.TrainingTurnResponse;
import interview.guide.modules.training.repository.TrainingSessionRepository;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import interview.guide.modules.training.repository.TrainingTurnRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 训练轮次和异步任务的只读查询服务。
 *
 * <p>轮询只读取数据库事实状态，不依赖 Redis 消息是否仍存在。实体在只读事务中立即映射
 * 为白名单响应，Controller 不接触 Entity，也不会意外触发事务外懒加载。
 */
@Service
@RequiredArgsConstructor
public class TrainingQueryService {

  private final TrainingTaskRepository taskRepository;
  private final TrainingSessionRepository sessionRepository;
  private final TrainingTurnRepository turnRepository;
  private final TrainingResponseMapper responseMapper;

  /**
   * 返回任务状态、本轮反馈与下一题，供前端使用单个接口轮询。
   */
  @Transactional(readOnly = true)
  public TrainingTaskPollResponse getTaskPollResult(
      String trainingId,
      String taskId
  ) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    String normalizedTaskId = requireId(taskId, "训练任务 ID 不能为空");
    TrainingTaskEntity task = taskRepository.findByTaskId(normalizedTaskId)
        .filter(entity -> normalizedTrainingId.equals(
            entity.getSession().getTrainingId()
        ))
        .orElseThrow(() -> new BusinessException(
            ErrorCode.TRAINING_TASK_FAILED,
            "训练任务不存在"
        ));
    return buildTaskPollResponse(task, normalizedTrainingId);
  }

  /**
   * 页面刷新后根据会话恢复最近任务。READY 会话尚未创建任务时返回 null，不把正常的
   * “尚未开始”状态伪装成任务失败。
   */
  @Transactional(readOnly = true)
  public TrainingTaskPollResponse getLatestTaskPollResult(String trainingId) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    if (!sessionRepository.existsByTrainingId(normalizedTrainingId)) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND);
    }
    return taskRepository
        .findFirstBySession_TrainingIdOrderByIdDesc(normalizedTrainingId)
        .map(task -> buildTaskPollResponse(task, normalizedTrainingId))
        .orElse(null);
  }

  private TrainingTaskPollResponse buildTaskPollResponse(
      TrainingTaskEntity task,
      String trainingId
  ) {
    TrainingTurnResponse evaluatedTurn = task.getSourceTurn() == null
        ? null
        : responseMapper.toTurnResponse(task.getSourceTurn());
    TrainingTurnResponse nextTurn = findNextTurn(task, trainingId);
    boolean retryable = task.getStatus() == TrainingTaskStatus.FAILED
        && isRetryableSessionState(task);
    return new TrainingTaskPollResponse(
        responseMapper.toTaskDTO(task),
        task.getSession().getStatus(),
        evaluatedTurn,
        nextTurn,
        task.getStatus().isTerminal(),
        retryable
    );
  }

  /**
   * 返回当前训练的公开轮次时间线，按题目顺序排列。
   */
  @Transactional(readOnly = true)
  public List<TrainingTurnResponse> listTurns(String trainingId) {
    String normalizedTrainingId = requireId(trainingId, "训练会话 ID 不能为空");
    /*
     * 先验证会话存在，再读取轮次，避免“空会话”和“伪造 ID”都返回空列表而让前端误判。
     * 任务查询的 EntityGraph 已经能验证会话，但轮次列表没有任务 ID，因此由 Repository
     * 的 exists 查询完成轻量校验。
     */
    if (!sessionRepository.existsByTrainingId(normalizedTrainingId)) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND);
    }
    return turnRepository.findBySession_TrainingIdOrderByTurnIndexAsc(normalizedTrainingId)
        .stream()
        .map(responseMapper::toTurnResponse)
        .toList();
  }

  private TrainingTurnResponse findNextTurn(
      TrainingTaskEntity task,
      String trainingId
  ) {
    if (task.getStatus() != TrainingTaskStatus.COMPLETED
        || task.getTaskType() == TrainingTaskType.SUMMARY) {
      return null;
    }
    int nextTurnIndex = task.getSourceTurn() == null
        ? 1
        : task.getSourceTurn().getTurnIndex() + 1;
    return turnRepository.findBySession_TrainingIdAndTurnIndex(
        trainingId,
        nextTurnIndex
    )
        .map(responseMapper::toTurnResponse)
        .orElse(null);
  }

  private boolean isRetryableSessionState(TrainingTaskEntity task) {
    return task.getTaskType() == TrainingTaskType.SUMMARY
        ? task.getSession().getStatus() == TrainingSessionStatus.SUMMARIZING
        : task.getSession().getStatus() == TrainingSessionStatus.IN_PROGRESS;
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
}
