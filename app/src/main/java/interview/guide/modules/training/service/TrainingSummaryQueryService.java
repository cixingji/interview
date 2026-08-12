package interview.guide.modules.training.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.training.model.TrainingSessionEntity;
import interview.guide.modules.training.model.TrainingSessionStatus;
import interview.guide.modules.training.model.TrainingSummaryEntity;
import interview.guide.modules.training.model.TrainingSummaryResponse;
import interview.guide.modules.training.model.TrainingSummaryTopicResult;
import interview.guide.modules.training.repository.TrainingSessionRepository;
import interview.guide.modules.training.repository.TrainingSummaryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 查询已经生成的公开训练总结。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingSummaryQueryService {

  private static final TypeReference<List<String>> STRING_LIST_TYPE =
      new TypeReference<>() {};
  private static final TypeReference<List<TrainingSummaryTopicResult>> TOPIC_LIST_TYPE =
      new TypeReference<>() {};

  private final TrainingSessionRepository sessionRepository;
  private final TrainingSummaryRepository summaryRepository;
  private final ObjectMapper objectMapper;

  /**
   * 总结尚在异步生成或训练尚未结束时返回 null；只有会话已经标记 COMPLETED 却缺少总结时
   * 才视为数据不一致。这样前端可以把 null 当作正常等待状态，而不是展示失败。
   */
  @Transactional(readOnly = true)
  public TrainingSummaryResponse getSummary(String trainingId) {
    String normalizedTrainingId = requireId(trainingId);
    TrainingSummaryEntity summary = summaryRepository
        .findBySession_TrainingId(normalizedTrainingId)
        .orElse(null);
    if (summary != null) {
      if (summary.getSession().getStatus() != TrainingSessionStatus.COMPLETED) {
        throw new BusinessException(
            ErrorCode.TRAINING_SUMMARY_FAILED,
            "训练总结状态不一致"
        );
      }
      return toResponse(summary);
    }

    TrainingSessionEntity session = sessionRepository.findByTrainingId(normalizedTrainingId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_SESSION_NOT_FOUND));
    if (session.getStatus() == TrainingSessionStatus.COMPLETED) {
      throw new BusinessException(
          ErrorCode.TRAINING_SUMMARY_FAILED,
          "训练已结束但总结不可用"
      );
    }
    return null;
  }

  private TrainingSummaryResponse toResponse(TrainingSummaryEntity summary) {
    return new TrainingSummaryResponse(
        summary.getSession().getTrainingId(),
        summary.getOverallScore(),
        summary.getCompletedQuestionCount(),
        summary.getCoveredTopicCount(),
        summary.getNarrative(),
        readJson(summary.getStrengthsJson(), STRING_LIST_TYPE, summary.getId()),
        readJson(summary.getImprovementsJson(), STRING_LIST_TYPE, summary.getId()),
        readJson(summary.getNextStepsJson(), STRING_LIST_TYPE, summary.getId()),
        readJson(summary.getTopicResultsJson(), TOPIC_LIST_TYPE, summary.getId()),
        summary.getGeneratedAt()
    );
  }

  private <T> T readJson(
      String json,
      TypeReference<T> type,
      Long summaryId
  ) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException e) {
      log.error("训练总结快照无法解析: summaryId={}", summaryId, e);
      throw new BusinessException(
          ErrorCode.TRAINING_SUMMARY_FAILED,
          "训练总结暂时不可用"
      );
    }
  }

  private String requireId(String trainingId) {
    if (trainingId == null || trainingId.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "训练会话 ID 不能为空");
    }
    String normalized = trainingId.trim();
    if (normalized.length() > 36) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "ID 长度不能超过 36 个字符");
    }
    return normalized;
  }
}
