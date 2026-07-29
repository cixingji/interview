package interview.guide.modules.training.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 一场训练中的弱项主题及其固定诊断快照。
 *
 * <p>{@code originalAverageScore}、{@code sampleCount} 和 {@code evidenceJson} 只在创建时写入。
 * 后续训练效果会记录在轮次与总结中，绝不覆盖原诊断数据，否则总结无法比较“训练前”和
 * “训练后”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "training_topics",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_training_topic_session_key",
            columnNames = {"training_session_id", "topic_key"}
        )
    },
    indexes = {
        @Index(
            name = "idx_training_topic_session_priority",
            columnList = "training_session_id,priority_rank"
        ),
        @Index(
            name = "idx_training_topic_session_status",
            columnList = "training_session_id,status"
        )
    }
)
public class TrainingTopicEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "training_session_id", nullable = false, updatable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private TrainingSessionEntity session;

  /**
   * 经过 trim 和 Locale.ROOT 小写化的稳定键，用于服务端比较和动作校验。
   */
  @Column(name = "topic_key", nullable = false, length = 255, updatable = false)
  private String topicKey;

  @Column(name = "display_name", nullable = false, length = 255, updatable = false)
  private String displayName;

  @Column(
      name = "original_average_score",
      nullable = false,
      precision = 5,
      scale = 2,
      updatable = false
  )
  private BigDecimal originalAverageScore;

  @Column(name = "sample_count", nullable = false, updatable = false)
  private Integer sampleCount;

  @Column(name = "priority_rank", nullable = false, updatable = false)
  private Integer priorityRank;

  /**
   * {@link TrainingEvidenceSnapshot} 列表的 JSON。该字段只允许服务端工具读取，
   * 对外 DTO 不包含它。
   */
  @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT", updatable = false)
  private String evidenceJson;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  @Builder.Default
  private TrainingTopicStatus status = TrainingTopicStatus.PENDING;

  @Column(name = "question_count", nullable = false)
  @Builder.Default
  private Integer questionCount = 0;

  @PrePersist
  protected void applyDefaults() {
    if (status == null) {
      status = TrainingTopicStatus.PENDING;
    }
    if (questionCount == null) {
      questionCount = 0;
    }
  }
}
