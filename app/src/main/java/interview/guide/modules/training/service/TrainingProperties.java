package interview.guide.modules.training.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * ReAct 训练的服务端安全边界。
 *
 * <p>这些配置只影响新创建的训练。创建服务会把关键限制复制到会话实体，确保进行中的训练
 * 不会因为配置热更新或服务重启而改变行为。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.training")
public class TrainingProperties {

  @Min(1)
  @Max(50)
  private int maxQuestions = 10;

  @Min(1)
  @Max(10)
  private int maxConsecutiveQuestionsPerTopic = 3;

  @Min(0)
  @Max(10)
  private int maxFollowUpsPerMainQuestion = 2;

  @Min(1)
  @Max(10)
  private int minimumTopicCount = 2;

  @Min(2)
  @Max(20)
  private int minimumEvidencePerTopic = 2;

  @Min(1)
  @Max(20)
  private int maxDiagnosticTopics = 5;

  @Min(1)
  @Max(20)
  private int maxEvidencePerTopic = 5;

  /**
   * 限制单次诊断读取的历史答案总量，避免历史数据增长后创建接口出现无界内存占用。
   */
  @Min(2)
  @Max(2000)
  private int maxSourceAnswers = 200;

  /**
   * QUEUED 超过该时间仍未被领取时重新投递。数据库状态不变，只刷新更新时间避免重复扫描。
   */
  @NotNull
  private Duration queuedStaleDuration = Duration.ofMinutes(2);

  /**
   * 处理中任务长时间没有心跳时视为执行节点失联，重置为 QUEUED 后重新投递。
   */
  @NotNull
  private Duration processingStaleDuration = Duration.ofMinutes(20);

  /**
   * 恢复调度间隔。Duration 由 Spring Boot 原生绑定，支持 30s、2m 等写法。
   */
  @NotNull
  private Duration recoveryInterval = Duration.ofMinutes(1);

  @Min(1)
  @Max(500)
  private int recoveryBatchSize = 50;

  /**
   * 防止配置把诊断所需样本数设得比实际允许读取或保存的证据数更大。
   */
  @AssertTrue(message = "训练证据上限不能小于每个主题的最低样本数")
  public boolean isEvidenceCapacityValid() {
    return maxEvidencePerTopic >= minimumEvidencePerTopic
        && maxSourceAnswers >= minimumEvidencePerTopic;
  }

  /**
   * 防止零值或负值造成调度器忙循环，也确保处理中超时大于等待投递超时。
   */
  @AssertTrue(message = "训练任务恢复时间必须为正数，且处理超时必须大于排队超时")
  public boolean isRecoveryTimingValid() {
    return queuedStaleDuration != null
        && processingStaleDuration != null
        && recoveryInterval != null
        && !queuedStaleDuration.isZero()
        && !queuedStaleDuration.isNegative()
        && !processingStaleDuration.isZero()
        && !processingStaleDuration.isNegative()
        && !recoveryInterval.isZero()
        && !recoveryInterval.isNegative()
        && processingStaleDuration.compareTo(queuedStaleDuration) > 0;
  }
}
