package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 按数据库中的可信 taskType 分发训练异步任务。
 *
 * <p>Redis 消息不携带任务类型，避免消息被伪造后选择错误处理器。消费者领取任务后，
 * 分发器重新读取数据库，只允许轮次 Runner 或总结 Runner 两条固定执行路径。
 */
@Service
@RequiredArgsConstructor
public class TrainingTaskProcessorDispatcher implements TrainingTurnTaskProcessor {

  private final TrainingTaskRepository taskRepository;
  private final BoundedReactTrainingRunner reactRunner;
  private final TrainingSummaryRunner summaryRunner;

  @Override
  public void process(String taskId) {
    TrainingTaskEntity task = taskRepository.findByTaskId(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED));
    switch (task.getTaskType()) {
      case INITIAL_TURN, ANSWER_TURN -> reactRunner.process(taskId);
      case SUMMARY -> summaryRunner.process(taskId);
    }
  }
}
