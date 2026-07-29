package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.training.model.TrainingSessionDTO;
import interview.guide.modules.training.model.TrainingSessionEntity;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingTopicDTO;
import interview.guide.modules.training.model.TrainingTopicDiagnostic;
import interview.guide.modules.training.model.TrainingTopicEntity;
import interview.guide.modules.training.model.TrainingTopicStatus;
import interview.guide.modules.training.repository.TrainingSessionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 训练会话创建与基础查询服务。
 *
 * <p>创建流程只执行数据库读取、内存诊断和数据库写入，整个事务内没有 LLM、S3 或 HTTP
 * 调用。会话与全部主题快照通过级联在同一事务提交，任何主题序列化或保存失败都会回滚，
 * 不会留下缺少诊断上下文的半成品会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingSessionService {

  private static final int SKILL_ID_MAX_LENGTH = 64;
  private static final int PROVIDER_ID_MAX_LENGTH = 64;

  private final InterviewAnswerRepository interviewAnswerRepository;
  private final TrainingSessionRepository trainingSessionRepository;
  private final TrainingDiagnosticSnapshotFactory snapshotFactory;
  private final TrainingProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * 根据指定简历和面试主题创建一场训练。
   *
   * <p>{@code resumeId} 为空时使用该 skill 下的通用面试历史；不为空时只使用该简历与
   * skill 的交集。至少一个主题必须拥有两条有效评分样本，具体最小值由配置控制。
   *
   * @param resumeId 可选的历史简历范围
   * @param skillId 必填的面试主题 ID
   * @param llmProvider 可选的 LLM Provider；为空时后续使用系统默认 Provider
   * @return 不包含历史证据的会话 DTO
   */
  @Transactional
  public TrainingSessionDTO createTrainingSession(
      Long resumeId,
      String skillId,
      String llmProvider
  ) {
    Long normalizedResumeId = validateResumeId(resumeId);
    String normalizedSkillId = requireIdentifier(
        skillId,
        "面试主题不能为空",
        SKILL_ID_MAX_LENGTH
    );
    String normalizedProvider = optionalIdentifier(llmProvider, PROVIDER_ID_MAX_LENGTH);

    List<InterviewAnswerEntity> evidence = interviewAnswerRepository.findTrainingEvidence(
        normalizedResumeId,
        normalizedSkillId,
        InterviewSessionEntity.SessionStatus.EVALUATED,
        PageRequest.of(0, properties.getMaxSourceAnswers())
    );
    List<TrainingTopicDiagnostic> diagnostics = snapshotFactory.build(evidence);
    if (diagnostics.isEmpty()) {
      throw new BusinessException(
          ErrorCode.TRAINING_HISTORY_INSUFFICIENT,
          "至少需要一个分类包含 "
              + properties.getMinimumEvidencePerTopic()
              + " 条已完成评估的有效回答"
      );
    }

    TrainingSessionEntity session = TrainingSessionEntity.builder()
        .trainingId(UUID.randomUUID().toString())
        .sourceResumeId(normalizedResumeId)
        .sourceSkillId(normalizedSkillId)
        .llmProvider(normalizedProvider)
        .status(TrainingSessionStatus.READY)
        .maxQuestions(properties.getMaxQuestions())
        .maxConsecutiveQuestionsPerTopic(
            properties.getMaxConsecutiveQuestionsPerTopic()
        )
        .maxFollowUpsPerMainQuestion(properties.getMaxFollowUpsPerMainQuestion())
        .minimumTopicCount(Math.min(properties.getMinimumTopicCount(), diagnostics.size()))
        .build();

    for (int index = 0; index < diagnostics.size(); index++) {
      TrainingTopicDiagnostic diagnostic = diagnostics.get(index);
      session.addTopic(toEntity(diagnostic, index + 1));
    }

    TrainingSessionEntity saved = trainingSessionRepository.save(session);
    log.info(
        "训练会话诊断快照已创建: trainingId={}, resumeId={}, skillId={}, topics={}, evidence={}",
        saved.getTrainingId(),
        normalizedResumeId,
        normalizedSkillId,
        diagnostics.size(),
        diagnostics.stream().mapToInt(TrainingTopicDiagnostic::sampleCount).sum()
    );
    return toDTO(saved);
  }

  /**
   * 查询会话及主题摘要。EntityGraph 会在事务内一次加载主题，DTO 映射后不再依赖懒加载。
   */
  @Transactional(readOnly = true)
  public TrainingSessionDTO getTrainingSession(String trainingId) {
    String normalizedTrainingId = requireIdentifier(trainingId, "训练会话 ID 不能为空", 36);
    TrainingSessionEntity session = trainingSessionRepository.findByTrainingId(normalizedTrainingId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND));
    return toDTO(session);
  }

  private TrainingTopicEntity toEntity(TrainingTopicDiagnostic diagnostic, int priorityRank) {
    try {
      return TrainingTopicEntity.builder()
          .topicKey(diagnostic.topicKey())
          .displayName(diagnostic.displayName())
          .originalAverageScore(diagnostic.averageScore())
          .sampleCount(diagnostic.sampleCount())
          .priorityRank(priorityRank)
          .evidenceJson(objectMapper.writeValueAsString(diagnostic.evidence()))
          .status(TrainingTopicStatus.PENDING)
          .build();
    } catch (JacksonException e) {
      log.error("序列化训练诊断证据失败: topicKey={}", diagnostic.topicKey(), e);
      throw new BusinessException(
          ErrorCode.TRAINING_SNAPSHOT_FAILED,
          "无法保存训练诊断快照"
      );
    }
  }

  private TrainingSessionDTO toDTO(TrainingSessionEntity session) {
    List<TrainingTopicDTO> topics = session.getTopics().stream()
        .sorted(Comparator.comparing(TrainingTopicEntity::getPriorityRank))
        .map(topic -> new TrainingTopicDTO(
            topic.getTopicKey(),
            topic.getDisplayName(),
            topic.getOriginalAverageScore(),
            topic.getSampleCount(),
            topic.getPriorityRank(),
            topic.getStatus(),
            topic.getQuestionCount()
        ))
        .toList();
    return new TrainingSessionDTO(
        session.getTrainingId(),
        session.getSourceResumeId(),
        session.getSourceSkillId(),
        session.getStatus(),
        session.getMaxQuestions(),
        session.getMaxConsecutiveQuestionsPerTopic(),
        session.getMaxFollowUpsPerMainQuestion(),
        session.getMinimumTopicCount(),
        session.getQuestionCount(),
        session.getCoveredTopicCount(),
        topics,
        session.getCreatedAt(),
        session.getUpdatedAt()
    );
  }

  private Long validateResumeId(Long resumeId) {
    if (resumeId != null && resumeId <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "简历 ID 必须大于 0");
    }
    return resumeId;
  }

  private String requireIdentifier(String value, String emptyMessage, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, emptyMessage);
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "标识长度不能超过 " + maxLength + " 个字符"
      );
    }
    return normalized;
  }

  private String optionalIdentifier(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return requireIdentifier(value, "标识不能为空", maxLength);
  }
}
