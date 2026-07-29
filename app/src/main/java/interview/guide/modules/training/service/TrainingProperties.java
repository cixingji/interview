package interview.guide.modules.training.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
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
   * 防止配置把诊断所需样本数设得比实际允许读取或保存的证据数更大。
   */
  @AssertTrue(message = "训练证据上限不能小于每个主题的最低样本数")
  public boolean isEvidenceCapacityValid() {
    return maxEvidencePerTopic >= minimumEvidencePerTopic
        && maxSourceAnswers >= minimumEvidencePerTopic;
  }
}
