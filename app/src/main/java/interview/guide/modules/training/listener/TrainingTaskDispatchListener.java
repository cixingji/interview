package interview.guide.modules.training.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 数据库提交成功后再投递 Redis Stream，消除“消息可见但任务尚未提交”的竞态。
 */
@Component
@RequiredArgsConstructor
public class TrainingTaskDispatchListener {

  private final TrainingTurnStreamProducer producer;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void dispatch(TrainingTaskQueuedEvent event) {
    producer.sendTrainingTask(event);
  }
}
