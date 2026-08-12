package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingEvidenceSnapshot;
import interview.guide.modules.training.model.TrainingExecutionContext;
import interview.guide.modules.training.model.TrainingExecutionContext.TopicSnapshot;
import interview.guide.modules.training.model.TrainingExecutionContext.TurnSnapshot;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskStatus;
import interview.guide.modules.training.model.TrainingTaskType;
import interview.guide.modules.training.model.TrainingTopicEntity;
import interview.guide.modules.training.model.TrainingTurnEntity;
import interview.guide.modules.training.repository.TrainingTaskRepository;
import interview.guide.modules.training.repository.TrainingTopicRepository;
import interview.guide.modules.training.repository.TrainingTurnRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 在短只读事务中装配 ReAct 执行快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingExecutionContextService {

  private static final TypeReference<List<TrainingEvidenceSnapshot>> EVIDENCE_LIST_TYPE =
      new TypeReference<>() {};

  private final TrainingTaskRepository taskRepository;
  private final TrainingTopicRepository topicRepository;
  private final TrainingTurnRepository turnRepository;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public TrainingExecutionContext load(String taskId) {
    TrainingTaskEntity task = taskRepository.findByTaskId(taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_TASK_FAILED, "训练任务不存在"));
    if (!task.getStatus().isProcessing()) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID, "训练任务未处于处理状态");
    }
    if (task.getTaskType() == TrainingTaskType.SUMMARY) {
      throw new BusinessException(
          ErrorCode.TRAINING_SESSION_STATE_INVALID,
          "总结任务不能进入训练轮次执行器"
      );
    }
    if (task.getSession().getStatus() != TrainingSessionStatus.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.TRAINING_SESSION_STATE_INVALID, "训练会话未处于进行状态");
    }

    String trainingId = task.getSession().getTrainingId();
    List<TopicSnapshot> topics = topicRepository
        .findBySession_TrainingIdOrderByPriorityRankAsc(trainingId)
        .stream()
        .map(this::toTopicSnapshot)
        .toList();
    if (topics.isEmpty()) {
      throw new BusinessException(
          ErrorCode.TRAINING_SNAPSHOT_FAILED,
          "训练诊断快照不包含可用主题"
      );
    }
    List<TrainingTurnEntity> turnEntities =
        turnRepository.findBySession_TrainingIdOrderByTurnIndexAsc(trainingId);
    TurnSnapshot sourceTurn = task.getSourceTurn() == null
        ? null
        : toTurnSnapshot(task.getSourceTurn());
    List<TurnSnapshot> previousTurns = turnEntities.stream()
        .filter(turn -> sourceTurn == null || turn.getTurnIndex() < sourceTurn.turnIndex())
        .map(this::toTurnSnapshot)
        .toList();

    if (task.getTaskType() == TrainingTaskType.ANSWER_TURN
        && (sourceTurn == null
            || sourceTurn.userAnswer() == null
            || sourceTurn.userAnswer().isBlank())) {
      throw new BusinessException(ErrorCode.TRAINING_TASK_FAILED, "训练回答不存在");
    }

    return new TrainingExecutionContext(
        task.getTaskId(),
        trainingId,
        task.getTaskType(),
        task.getSession().getSourceSkillId(),
        task.getSession().getLlmProvider(),
        task.getSession().getStatus(),
        task.getSession().getMaxQuestions(),
        task.getSession().getMaxConsecutiveQuestionsPerTopic(),
        task.getSession().getMaxFollowUpsPerMainQuestion(),
        task.getSession().getMinimumTopicCount(),
        task.getSession().getQuestionCount(),
        task.getSession().getCoveredTopicCount(),
        task.getSession().getCurrentTopicKey(),
        task.getSession().getCurrentMainQuestionIndex(),
        task.getSession().getConsecutiveTopicQuestionCount(),
        task.getSession().getCurrentMainQuestionFollowUpCount(),
        sourceTurn,
        topics,
        previousTurns
    );
  }

  private TopicSnapshot toTopicSnapshot(TrainingTopicEntity topic) {
    return new TopicSnapshot(
        topic.getTopicKey(),
        topic.getDisplayName(),
        topic.getOriginalAverageScore(),
        topic.getSampleCount(),
        topic.getPriorityRank(),
        topic.getStatus(),
        topic.getQuestionCount(),
        readEvidence(topic)
    );
  }

  private List<TrainingEvidenceSnapshot> readEvidence(TrainingTopicEntity topic) {
    try {
      List<TrainingEvidenceSnapshot> evidence = objectMapper.readValue(
          topic.getEvidenceJson(),
          EVIDENCE_LIST_TYPE
      );
      return evidence == null ? List.of() : List.copyOf(evidence);
    } catch (JacksonException e) {
      log.error("训练诊断快照无法解析: topicId={}, topicKey={}",
          topic.getId(), topic.getTopicKey(), e);
      throw new BusinessException(
          ErrorCode.TRAINING_SNAPSHOT_FAILED,
          "训练诊断快照不可用"
      );
    }
  }

  private TurnSnapshot toTurnSnapshot(TrainingTurnEntity turn) {
    return new TurnSnapshot(
        turn.getTurnId(),
        turn.getTurnIndex(),
        turn.getMainQuestionIndex(),
        turn.getAction(),
        turn.getTopicKey(),
        turn.getQuestion(),
        turn.getUserAnswer(),
        turn.getFeedback(),
        turn.getInternalScore(),
        turn.getReferenceAnswer(),
        turn.getStatus()
    );
  }
}
