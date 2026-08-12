package interview.guide.modules.training.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 训练任务状态，同时作为前端轮询展示的粗粒度进度。
 *
 * <p>不会暴露模型 Thought、工具参数或原始异常。前端只能看到当前阶段和经过清洗的失败提示。
 */
public enum TrainingTaskStatus {
  QUEUED,
  ANALYZING,
  RETRIEVING,
  DECIDING,
  GENERATING,
  COMPLETED,
  FAILED;

  private static final Set<TrainingTaskStatus> PROCESSING_STATUSES = EnumSet.of(
      ANALYZING,
      RETRIEVING,
      DECIDING,
      GENERATING
  );

  public boolean isProcessing() {
    return PROCESSING_STATUSES.contains(this);
  }

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED;
  }

  public static Set<TrainingTaskStatus> processingStatuses() {
    return Set.copyOf(PROCESSING_STATUSES);
  }
}
