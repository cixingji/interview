package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingSummaryContext;
import interview.guide.modules.training.model.TrainingSummaryContext.TurnSummary;
import interview.guide.modules.training.model.TrainingSummaryTopicResult;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskType;
import interview.guide.modules.training.model.TrainingTopicEntity;
import interview.guide.modules.training.model.TrainingTurnEntity;
import interview.guide.modules.training.model.TrainingTurnStatus;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import interview.guide.modules.training.repository.TrainingTopicRepository;
import interview.guide.modules.training.repository.TrainingTurnRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在只读事务中计算训练总结所需的可信统计快照。
 *
 * <p>分数只来自数据库中已完成轮次的 internalScore。LLM 收到的是服务端已经算好的总分、
 * 主题均分和有限反馈，它不能回传或覆盖任何数值结果。
 */
@Service
@RequiredArgsConstructor
public class TrainingSummaryContextService {

  private final TrainingTaskRepository taskRepository;
  private final TrainingTopicRepository topicRepository;
  private final TrainingTurnRepository turnRepository;

  @Transactional(readOnly = true)
  public TrainingSummaryContext load(String taskId) {
    TrainingTaskEntity task = taskRepository.findByTaskId(taskId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.TRAINING_SUMMARY_FAILED,
            "训练总结任务不存在"
        ));
    if (task.getTaskType() != TrainingTaskType.SUMMARY
        || !task.getStatus().isProcessing()
        || task.getSession().getStatus() != TrainingSessionStatus.SUMMARIZING) {
      throw new BusinessException(
          ErrorCode.TRAINING_SESSION_STATE_INVALID,
          "训练总结任务状态无效"
      );
    }

    String trainingId = task.getSession().getTrainingId();
    List<TrainingTurnEntity> turns =
        turnRepository.findBySession_TrainingIdOrderByTurnIndexAsc(trainingId);
    validateCompletedTurns(turns, task.getSession().getQuestionCount());
    List<TrainingTopicEntity> topics =
        topicRepository.findBySession_TrainingIdOrderByPriorityRankAsc(trainingId);
    List<TrainingSummaryTopicResult> topicResults = topics.stream()
        .map(topic -> aggregateTopic(topic, turns))
        .filter(result -> result.answeredQuestionCount() > 0)
        .toList();
    if (topicResults.isEmpty()) {
      throw new BusinessException(
          ErrorCode.TRAINING_SUMMARY_FAILED,
          "训练总结没有可聚合的主题"
      );
    }
    if (topicResults.size() != task.getSession().getCoveredTopicCount()) {
      throw new BusinessException(
          ErrorCode.TRAINING_SUMMARY_FAILED,
          "训练主题覆盖计数与轮次不一致"
      );
    }

    int overallScore = roundedAverage(
        turns.stream().map(TrainingTurnEntity::getInternalScore).toList()
    );
    List<TurnSummary> turnSummaries = turns.stream()
        .map(turn -> new TurnSummary(
            turn.getTurnIndex(),
            turn.getTopicKey(),
            turn.getQuestion(),
            turn.getFeedback()
        ))
        .toList();
    return new TrainingSummaryContext(
        task.getTaskId(),
        trainingId,
        task.getSession().getLlmProvider(),
        overallScore,
        turns.size(),
        task.getSession().getCoveredTopicCount(),
        topicResults,
        turnSummaries
    );
  }

  private void validateCompletedTurns(
      List<TrainingTurnEntity> turns,
      int expectedQuestionCount
  ) {
    boolean invalid = turns.isEmpty()
        || turns.size() != expectedQuestionCount
        || turns.stream().anyMatch(turn ->
            turn.getStatus() != TrainingTurnStatus.COMPLETED
                || turn.getInternalScore() == null
                || turn.getInternalScore() < 0
                || turn.getInternalScore() > 100
        );
    if (invalid) {
      throw new BusinessException(
          ErrorCode.TRAINING_SUMMARY_FAILED,
          "训练轮次尚未形成完整评分"
      );
    }
  }

  private TrainingSummaryTopicResult aggregateTopic(
      TrainingTopicEntity topic,
      List<TrainingTurnEntity> turns
  ) {
    List<Integer> scores = turns.stream()
        .filter(turn -> topic.getTopicKey().equals(turn.getTopicKey()))
        .map(TrainingTurnEntity::getInternalScore)
        .toList();
    return new TrainingSummaryTopicResult(
        topic.getTopicKey(),
        topic.getDisplayName(),
        topic.getOriginalAverageScore(),
        scores.isEmpty() ? 0 : roundedAverage(scores),
        scores.size()
    );
  }

  private int roundedAverage(List<Integer> scores) {
    long total = scores.stream().mapToLong(Integer::longValue).sum();
    return BigDecimal.valueOf(total)
        .divide(BigDecimal.valueOf(scores.size()), 0, RoundingMode.HALF_UP)
        .intValue();
  }
}
