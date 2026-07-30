package interview.guide.modules.training.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.training.service.TrainingTaskStateService;
import interview.guide.modules.training.service.TrainingTurnTaskProcessor;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ReAct 训练轮次 Stream 消费者。
 *
 * <p>消费者只负责可靠交付：解析 ID、原子领取、调用处理器和处理重试。第四步实现的
 * TrainingTurnTaskProcessor 才负责工具调用和动作决策，这样基础设施不会依赖推理细节。
 */
@Slf4j
@Component
public class TrainingTurnStreamConsumer
    extends AbstractStreamConsumer<TrainingTurnStreamConsumer.TrainingTurnPayload> {

  private final TrainingTaskStateService stateService;
  private final TrainingTurnStreamProducer producer;
  private final ObjectProvider<TrainingTurnTaskProcessor> processorProvider;

  public record TrainingTurnPayload(String taskId, String trainingId) {
  }

  public TrainingTurnStreamConsumer(
      RedisService redisService,
      TrainingTaskStateService stateService,
      TrainingTurnStreamProducer producer,
      ObjectProvider<TrainingTurnTaskProcessor> processorProvider
  ) {
    super(redisService);
    this.stateService = stateService;
    this.producer = producer;
    this.processorProvider = processorProvider;
  }

  @Override
  protected String taskDisplayName() {
    return "训练轮次";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.TRAINING_TURN_STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.TRAINING_TURN_GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.TRAINING_TURN_CONSUMER_PREFIX;
  }

  @Override
  protected String threadName() {
    return "training-turn-consumer";
  }

  @Override
  protected TrainingTurnPayload parsePayload(
      StreamMessageId messageId,
      Map<String, String> data
  ) {
    String taskId = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
    String trainingId = data.get(AsyncTaskStreamConstants.FIELD_TRAINING_ID);
    if (taskId == null || taskId.isBlank() || trainingId == null || trainingId.isBlank()) {
      log.warn("训练轮次消息缺少必要 ID，丢弃: messageId={}", messageId);
      return null;
    }
    return new TrainingTurnPayload(taskId, trainingId);
  }

  @Override
  protected String payloadIdentifier(TrainingTurnPayload payload) {
    return "trainingId=" + payload.trainingId() + ", taskId=" + payload.taskId();
  }

  @Override
  protected boolean shouldSkip(TrainingTurnPayload payload) {
    // 任务已删除、已完成、已失败或已被其他消费者领取时，直接 ACK 重复消息。
    return stateService.shouldSkip(payload.taskId(), payload.trainingId());
  }

  @Override
  protected void markProcessing(TrainingTurnPayload payload) {
    // 训练任务使用 tryMarkProcessing 的数据库条件更新领取。
  }

  @Override
  protected boolean tryMarkProcessing(TrainingTurnPayload payload) {
    return stateService.tryMarkProcessing(payload.taskId(), payload.trainingId());
  }

  @Override
  protected void processBusiness(TrainingTurnPayload payload) {
    TrainingTurnTaskProcessor processor = processorProvider.getIfAvailable();
    if (processor == null) {
      throw new BusinessException(
          ErrorCode.TRAINING_TASK_FAILED,
          "训练执行器尚未配置"
      );
    }
    processor.process(payload.taskId());
  }

  @Override
  protected void markCompleted(TrainingTurnPayload payload) {
    stateService.markCompleted(payload.taskId());
  }

  @Override
  protected void markFailed(TrainingTurnPayload payload, String error) {
    // 对外只记录固定安全提示，error 仅由基础消费者写入服务端日志。
    stateService.markFailed(payload.taskId());
  }

  @Override
  protected void retryMessage(TrainingTurnPayload payload, int retryCount) {
    if (!stateService.resetForRetry(payload.taskId(), retryCount)) {
      log.info("训练轮次任务状态已改变，不再自动重试: taskId={}", payload.taskId());
      return;
    }
    producer.sendTrainingTask(new TrainingTaskQueuedEvent(
        payload.taskId(),
        payload.trainingId(),
        retryCount
    ));
  }
}
