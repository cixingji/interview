package interview.guide.modules.training.model;

/**
 * 前端轮询一次训练异步任务时获得的完整安全视图。
 *
 * <p>ANSWER_TURN 的 evaluatedTurn 是用户刚回答并等待/完成评估的轮次；INITIAL_TURN
 * 没有被评估轮次，因此该字段为空。任务完成且会话仍继续时，nextTurn 返回下一道待答题。
 */
public record TrainingTaskPollResponse(
    TrainingTaskDTO task,
    TrainingSessionStatus sessionStatus,
    TrainingTurnResponse evaluatedTurn,
    TrainingTurnResponse nextTurn,
    boolean terminal,
    boolean retryable
) {
}
