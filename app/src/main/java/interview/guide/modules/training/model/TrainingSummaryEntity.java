package interview.guide.modules.training.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 一场训练完成后的不可变总结。
 *
 * <p>列表和主题聚合使用 JSON 文本保存，作为生成时快照。总结生成后训练会话不可继续答题，
 * 因此无需随轮次变化重算，也不会因为后续 Prompt 或配置调整改变历史报告。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "training_summaries",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_training_summary_session",
            columnNames = "training_session_id"
        )
    }
)
public class TrainingSummaryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "training_session_id", nullable = false, updatable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private TrainingSessionEntity session;

  @Column(name = "overall_score", nullable = false, updatable = false)
  private Integer overallScore;

  @Column(name = "completed_question_count", nullable = false, updatable = false)
  private Integer completedQuestionCount;

  @Column(name = "covered_topic_count", nullable = false, updatable = false)
  private Integer coveredTopicCount;

  @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
  private String narrative;

  @Column(
      name = "strengths_json",
      nullable = false,
      columnDefinition = "TEXT",
      updatable = false
  )
  private String strengthsJson;

  @Column(
      name = "improvements_json",
      nullable = false,
      columnDefinition = "TEXT",
      updatable = false
  )
  private String improvementsJson;

  @Column(
      name = "next_steps_json",
      nullable = false,
      columnDefinition = "TEXT",
      updatable = false
  )
  private String nextStepsJson;

  @Column(
      name = "topic_results_json",
      nullable = false,
      columnDefinition = "TEXT",
      updatable = false
  )
  private String topicResultsJson;

  @Column(name = "generated_at", nullable = false, updatable = false)
  private LocalDateTime generatedAt;

  @PrePersist
  protected void onCreate() {
    if (generatedAt == null) {
      generatedAt = LocalDateTime.now();
    }
  }
}
