package interview.guide.modules.training.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingSummaryCandidate;
import interview.guide.modules.training.model.TrainingSummaryContent;
import interview.guide.modules.training.model.TrainingSummaryContext;
import interview.guide.modules.training.model.TrainingTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/**
 * 独立的异步训练总结 Runner。
 *
 * <p>总结任务不调用工具，只允许结构化输出最多两次尝试。它是独立数据库任务，因此不会
 * 占用或突破训练轮次最多四次 LLM 调用的预算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingSummaryRunner {

  private static final int MAX_SUMMARY_LLM_CALLS = 2;

  private final TrainingSummaryContextService contextService;
  private final TrainingSummaryPromptFactory promptFactory;
  private final TrainingSummaryPolicy summaryPolicy;
  private final TrainingSummaryPersistenceService persistenceService;
  private final TrainingTaskStateService taskStateService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;

  private final BeanOutputConverter<TrainingSummaryCandidate> outputConverter =
      new BeanOutputConverter<>(TrainingSummaryCandidate.class);

  public void process(String taskId) {
    TrainingSummaryContext context = contextService.load(taskId);
    requireGenerating(taskId);
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(context.llmProvider());
    TrainingSummaryCandidate candidate = structuredOutputInvoker.invoke(
        chatClient,
        promptFactory.buildSystemPrompt(outputConverter.getFormat()),
        promptFactory.buildUserPrompt(context),
        outputConverter,
        ErrorCode.TRAINING_SUMMARY_FAILED,
        "训练总结生成失败",
        "ReAct训练总结",
        log,
        MAX_SUMMARY_LLM_CALLS
    );
    taskStateService.touchProcessing(taskId);
    TrainingSummaryContent content = summaryPolicy.resolve(context, candidate);
    persistenceService.apply(taskId, context, content);
  }

  private void requireGenerating(String taskId) {
    if (!taskStateService.advanceProgress(
        taskId,
        TrainingTaskStatus.ANALYZING,
        TrainingTaskStatus.GENERATING
    )) {
      throw new BusinessException(
          ErrorCode.TRAINING_SESSION_STATE_INVALID,
          "训练总结任务进度已改变"
      );
    }
  }
}
