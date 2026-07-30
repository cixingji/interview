package interview.guide.modules.training.listener;

import interview.guide.modules.training.service.TrainingProperties;
import interview.guide.modules.training.service.TrainingTaskStateService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 恢复 Redis 投递失败或执行节点异常退出后遗留的训练任务。
 *
 * <p>每轮最多扫描 recoveryBatchSize 条，避免故障恢复挤占正常请求。状态服务会逐条加锁并
 * 再次检查更新时间，锁事务返回后才发送 Redis 消息，因此网络调用不在数据库事务内。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingTaskRecoveryScheduler {

  private final TrainingTaskStateService stateService;
  private final TrainingTurnStreamProducer producer;
  private final TrainingProperties properties;

  @Scheduled(
      fixedDelayString = "#{@trainingProperties.recoveryInterval.toMillis()}",
      initialDelayString = "#{@trainingProperties.recoveryInterval.toMillis()}"
  )
  public void recoverStaleTasks() {
    LocalDateTime now = LocalDateTime.now();
    recoverQueued(now.minus(properties.getQueuedStaleDuration()));
    recoverProcessing(now.minus(properties.getProcessingStaleDuration()));
  }

  private void recoverQueued(LocalDateTime threshold) {
    List<String> taskIds = stateService.findStaleQueuedTaskIds(threshold);
    for (String taskId : taskIds) {
      stateService.prepareQueuedRecovery(taskId, threshold).ifPresent(event -> {
        if (producer.sendTrainingTask(event)) {
          log.info(
              "重新投递等待中的训练任务: trainingId={}, taskId={}",
              event.trainingId(),
              event.taskId()
          );
        }
      });
    }
  }

  private void recoverProcessing(LocalDateTime threshold) {
    List<String> taskIds = stateService.findStaleProcessingTaskIds(threshold);
    for (String taskId : taskIds) {
      stateService.prepareProcessingRecovery(taskId, threshold).ifPresent(event -> {
        if (producer.sendTrainingTask(event)) {
          log.warn(
              "恢复失去心跳的训练任务: trainingId={}, taskId={}",
              event.trainingId(),
              event.taskId()
          );
        }
      });
    }
  }
}
