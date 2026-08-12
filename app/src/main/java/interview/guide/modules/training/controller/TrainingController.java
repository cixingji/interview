package interview.guide.modules.training.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.annotation.RateLimit.TimeUnit;
import interview.guide.common.result.Result;
import interview.guide.modules.training.model.CreateTrainingSessionRequest;
import interview.guide.modules.training.model.SubmitTrainingAnswerRequest;
import interview.guide.modules.training.model.TrainingSessionDTO;
import interview.guide.modules.training.model.TrainingSummaryResponse;
import interview.guide.modules.training.model.TrainingTaskDTO;
import interview.guide.modules.training.model.TrainingTaskPollResponse;
import interview.guide.modules.training.model.TrainingTurnResponse;
import interview.guide.modules.training.service.TrainingQueryService;
import interview.guide.modules.training.service.TrainingSessionService;
import interview.guide.modules.training.service.TrainingSummaryQueryService;
import interview.guide.modules.training.service.TrainingTaskStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ReAct 弱项训练 REST 接口。
 *
 * <p>所有耗时处理都只创建异步任务并立即返回 taskId。前端通过任务轮询接口读取数据库
 * 状态、本轮反馈和下一题，Controller 不等待 LLM，也不接触 Entity 或 Redis。
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/training")
@Tag(name = "弱项训练", description = "ReAct 弱项训练会话、异步问答和任务轮询")
public class TrainingController {

  private final TrainingSessionService sessionService;
  private final TrainingTaskStateService taskStateService;
  private final TrainingQueryService queryService;
  private final TrainingSummaryQueryService summaryQueryService;

  @PostMapping("/sessions")
  @Operation(
      summary = "创建弱项训练会话",
      description = "根据已评估的历史回答固化诊断快照，不调用 LLM；简历范围和模型供应商可选"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingSessionDTO> createSession(
      @Valid @RequestBody CreateTrainingSessionRequest request
  ) {
    return Result.success(sessionService.createTrainingSession(
        request.resumeId(),
        request.skillId(),
        request.llmProvider()
    ));
  }

  @GetMapping("/sessions/{trainingId}")
  @Operation(
      summary = "查询训练会话",
      description = "返回会话进度和公开主题摘要，不返回历史证据或内部评分"
  )
  public Result<TrainingSessionDTO> getSession(
      @PathVariable String trainingId
  ) {
    return Result.success(sessionService.getTrainingSession(trainingId));
  }

  @PostMapping("/sessions/{trainingId}/start")
  @Operation(
      summary = "启动训练",
      description = "幂等创建首题异步任务并立即返回 taskId，客户端随后轮询任务状态"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingTaskDTO> startTraining(
      @PathVariable String trainingId
  ) {
    return Result.success(taskStateService.createInitialTask(trainingId));
  }

  @PostMapping("/sessions/{trainingId}/turns/{turnId}/answers")
  @Operation(
      summary = "提交本轮回答",
      description = "保存回答并幂等创建评估决策任务，不在 HTTP 请求内等待 LLM"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 300, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingTaskDTO> submitAnswer(
      @PathVariable String trainingId,
      @PathVariable String turnId,
      @Valid @RequestBody SubmitTrainingAnswerRequest request
  ) {
    return Result.success(taskStateService.queueAnswerTask(
        trainingId,
        turnId,
        request.answer()
    ));
  }

  @GetMapping("/sessions/{trainingId}/tasks/{taskId}")
  @Operation(
      summary = "轮询指定训练任务",
      description = "返回粗粒度处理阶段、本轮公开反馈和下一题，不暴露 ReAct 内部推理"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3_000, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 180, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingTaskPollResponse> pollTask(
      @PathVariable String trainingId,
      @PathVariable String taskId
  ) {
    return Result.success(queryService.getTaskPollResult(trainingId, taskId));
  }

  @GetMapping("/sessions/{trainingId}/tasks/latest")
  @Operation(
      summary = "查询会话最新任务",
      description = "用于页面刷新恢复和等待异步总结任务；尚未开始的 READY 会话返回空数据"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3_000, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 180, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingTaskPollResponse> getLatestTask(
      @PathVariable String trainingId
  ) {
    return Result.success(queryService.getLatestTaskPollResult(trainingId));
  }

  @PostMapping("/sessions/{trainingId}/tasks/{taskId}/retry")
  @Operation(
      summary = "重试失败任务",
      description = "仅允许重试后端判定为 retryable 且仍符合会话状态约束的任务"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 60, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingTaskDTO> retryTask(
      @PathVariable String trainingId,
      @PathVariable String taskId
  ) {
    return Result.success(taskStateService.retryFailedTask(trainingId, taskId));
  }

  @GetMapping("/sessions/{trainingId}/turns")
  @Operation(
      summary = "查询训练轮次",
      description = "按题目顺序返回公开问题、回答、反馈和参考答案，不包含单题内部评分"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2_000, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 120, timeUnit = TimeUnit.MINUTES)
  public Result<List<TrainingTurnResponse>> listTurns(
      @PathVariable String trainingId
  ) {
    return Result.success(queryService.listTurns(trainingId));
  }

  @GetMapping("/sessions/{trainingId}/summary")
  @Operation(
      summary = "查询训练总结",
      description = "总结生成期间返回空数据，完成后返回服务端聚合总分、主题分和改进建议"
  )
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 1_000, timeUnit = TimeUnit.MINUTES)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 60, timeUnit = TimeUnit.MINUTES)
  public Result<TrainingSummaryResponse> getSummary(
      @PathVariable String trainingId
  ) {
    return Result.success(summaryQueryService.getSummary(trainingId));
  }
}
