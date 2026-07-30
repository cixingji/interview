package interview.guide.modules.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReAct 训练安全配置")
class TrainingPropertiesTest {

  @Test
  @DisplayName("默认配置满足证据容量和任务恢复时序约束")
  void defaultsAreInternallyConsistent() {
    TrainingProperties properties = new TrainingProperties();

    assertThat(properties.isEvidenceCapacityValid()).isTrue();
    assertThat(properties.isRecoveryTimingValid()).isTrue();
  }

  @Test
  @DisplayName("最低证据数超过保存容量时配置无效")
  void evidenceMinimumCannotExceedCapacity() {
    TrainingProperties properties = new TrainingProperties();
    properties.setMinimumEvidencePerTopic(6);
    properties.setMaxEvidencePerTopic(5);

    assertThat(properties.isEvidenceCapacityValid()).isFalse();
  }

  @Test
  @DisplayName("处理超时不大于排队超时时配置无效")
  void processingTimeoutMustBeLongerThanQueuedTimeout() {
    TrainingProperties properties = new TrainingProperties();
    properties.setQueuedStaleDuration(Duration.ofMinutes(5));
    properties.setProcessingStaleDuration(Duration.ofMinutes(5));

    assertThat(properties.isRecoveryTimingValid()).isFalse();
  }
}
