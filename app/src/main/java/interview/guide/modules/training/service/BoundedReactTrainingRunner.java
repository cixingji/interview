package interview.guide.modules.training.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingAction;
import interview.guide.modules.training.model.TrainingActionState;
import interview.guide.modules.training.model.TrainingDecisionCandidate;
import interview.guide.modules.training.model.TrainingExecutionContext;
import interview.guide.modules.training.model.TrainingResolvedDecision;
import interview.guide.modules.training.model.TrainingTaskStatus;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * 训练轮次的有界 ReAct Runner。
 *
 * <p>每个任务最多进行三轮模型分析/工具请求，并为最终结构化决定预留至少一次调用；
 * 单次任务执行的模型调用总数硬限制为四次。工具循环由服务端显式驱动，关闭 ChatClient
 * 自动 ToolCallingAdvisor，避免框架在内部继续递归而绕过预算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoundedReactTrainingRunner {

  private static final int MAX_TOOL_ROUNDS = 3;
  private static final int MAX_LLM_CALLS = 4;

  private final TrainingExecutionContextService contextService;
  private final TrainingReadOnlyToolFactory toolFactory;
  private final TrainingActionPolicy actionPolicy;
  private final TrainingPromptFactory promptFactory;
  private final TrainingTaskStateService taskStateService;
  private final TrainingDecisionPersistenceService persistenceService;
  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final ToolCallingManager toolCallingManager;

  private final BeanOutputConverter<TrainingDecisionCandidate> outputConverter =
      new BeanOutputConverter<>(TrainingDecisionCandidate.class);

  public void process(String taskId) {
    TrainingExecutionContext context = contextService.load(taskId);
    TrainingActionState actionState = actionPolicy.from(context);
    Set<TrainingAction> allowedActions = actionPolicy.allowedActions(actionState);
    TrainingReadOnlyTools tools = toolFactory.create(context);
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(context.llmProvider());

    int reasoningCalls = runBoundedToolLoop(
        chatClient,
        context,
        allowedActions,
        tools
    );
    requireProgress(
        taskId,
        tools.observations().isEmpty()
            ? TrainingTaskStatus.ANALYZING
            : TrainingTaskStatus.RETRIEVING,
        TrainingTaskStatus.DECIDING
    );

    int remainingCalls = MAX_LLM_CALLS - reasoningCalls;
    TrainingDecisionCandidate candidate = structuredOutputInvoker.invoke(
        chatClient,
        promptFactory.buildDecisionSystemPrompt(outputConverter.getFormat()),
        promptFactory.buildDecisionUserPrompt(
            context,
            allowedActions,
            tools.observations()
        ),
        outputConverter,
        ErrorCode.TRAINING_TASK_FAILED,
        "训练决定生成失败",
        "ReAct训练决定",
        log,
        remainingCalls
    );
    requireProgress(
        taskId,
        TrainingTaskStatus.DECIDING,
        TrainingTaskStatus.GENERATING
    );

    TrainingResolvedDecision resolved = actionPolicy.resolve(actionState, candidate);
    TrainingResolvedDecision persisted = persistenceService.apply(taskId, resolved);
    log.info(
        "ReAct训练轮次执行完成: trainingId={}, taskId={}, reasoningCalls={}, action={}, fallback={}",
        context.trainingId(),
        taskId,
        reasoningCalls,
        persisted.action(),
        persisted.fallbackApplied()
    );
  }

  private int runBoundedToolLoop(
      ChatClient chatClient,
      TrainingExecutionContext context,
      Set<TrainingAction> allowedActions,
      TrainingReadOnlyTools tools
  ) {
    ToolCallback[] callbacks = ToolCallbacks.from(tools);
    ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
        .toolCallbacks(callbacks)
        .build();
    Prompt prompt = new Prompt(
        List.of(
            new SystemMessage(promptFactory.buildReasoningSystemPrompt()),
            new UserMessage(
                promptFactory.buildReasoningUserPrompt(context, allowedActions)
            )
        ),
        chatOptions
    );

    int reasoningCalls = 1;
    int toolRounds = 0;
    ChatClientResponse response = callWithoutAutomaticTools(chatClient, prompt, chatOptions);
    taskStateService.touchProcessing(context.taskId());
    while (hasToolCalls(response) && toolRounds < MAX_TOOL_ROUNDS) {
      if (toolRounds == 0) {
        requireProgress(
            context.taskId(),
            TrainingTaskStatus.ANALYZING,
            TrainingTaskStatus.RETRIEVING
        );
      } else {
        taskStateService.touchProcessing(context.taskId());
      }

      ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(
          prompt,
          response.chatResponse()
      );
      toolRounds++;
      prompt = new Prompt(toolResult.conversationHistory(), chatOptions);
      taskStateService.touchProcessing(context.taskId());
      if (toolRounds >= MAX_TOOL_ROUNDS || reasoningCalls >= MAX_TOOL_ROUNDS) {
        break;
      }
      response = callWithoutAutomaticTools(chatClient, prompt, chatOptions);
      reasoningCalls++;
      taskStateService.touchProcessing(context.taskId());
    }
    return reasoningCalls;
  }

  private ChatClientResponse callWithoutAutomaticTools(
      ChatClient chatClient,
      Prompt prompt,
      ToolCallingChatOptions chatOptions
  ) {
    ChatClientResponse response = chatClient.prompt()
        .messages(prompt.getInstructions())
        .options(chatOptions)
        .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
        .call()
        .chatClientResponse();
    if (response == null || response.chatResponse() == null) {
      throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "训练模型返回空响应");
    }
    return response;
  }

  private boolean hasToolCalls(ChatClientResponse response) {
    return response != null
        && response.chatResponse() != null
        && response.chatResponse().hasToolCalls();
  }

  private void requireProgress(
      String taskId,
      TrainingTaskStatus expected,
      TrainingTaskStatus next
  ) {
    if (!taskStateService.advanceProgress(taskId, expected, next)) {
      throw new BusinessException(
          ErrorCode.TRAINING_SESSION_STATE_INVALID,
          "训练任务进度已改变"
      );
    }
  }
}
