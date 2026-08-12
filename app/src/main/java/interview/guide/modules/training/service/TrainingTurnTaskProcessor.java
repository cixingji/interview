package interview.guide.modules.training.service;

/**
 * Redis 消费者与训练任务分发器之间的边界。
 *
 * <p>消费者只依赖该接口。分发器再根据数据库 taskType 选择有界 ReAct 轮次 Runner 或
 * 总结 Runner，使异步基础设施不依赖具体 Prompt、工具或总结实现。
 */
public interface TrainingTurnTaskProcessor {

  void process(String taskId);
}
