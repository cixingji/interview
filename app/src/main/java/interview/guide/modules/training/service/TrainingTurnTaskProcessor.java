package interview.guide.modules.training.service;

/**
 * Redis 消费者与具体 ReAct 执行器之间的边界。
 *
 * <p>第三部分只负责可靠投递和状态机。第四部分提供该接口的 Spring Bean 实现，
 * 在消费者线程中执行有界工具循环；这样异步基础设施不依赖具体 Prompt 或工具实现。
 */
public interface TrainingTurnTaskProcessor {

  void process(String taskId);
}
