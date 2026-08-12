package interview.guide.modules.training.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.training.service.TrainingTaskStateService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 训练任务生产者。消息只携带稳定 ID，不携带任务类型、回答、历史证据或模型上下文。
 */
@Component
public class TrainingTurnStreamProducer
    extends AbstractStreamProducer<TrainingTaskQueuedEvent> {

  private final TrainingTaskStateService stateService;

  public TrainingTurnStreamProducer(
      RedisService redisService,
      TrainingTaskStateService stateService
  ) {
    super(redisService);
    this.stateService = stateService;
  }

  public boolean sendTrainingTask(TrainingTaskQueuedEvent event) {
    return sendTask(event);
  }

  @Override
  protected String taskDisplayName() {
    return "训练";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.TRAINING_TURN_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(TrainingTaskQueuedEvent event) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_TASK_ID, event.taskId(),
        AsyncTaskStreamConstants.FIELD_TRAINING_ID, event.trainingId(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(event.retryCount())
    );
  }

  @Override
  protected String payloadIdentifier(TrainingTaskQueuedEvent event) {
    return "trainingId=" + event.trainingId() + ", taskId=" + event.taskId();
  }

  @Override
  protected void onSendFailed(TrainingTaskQueuedEvent event, String error) {
    stateService.recordDispatchFailure(event.taskId());
  }
}
