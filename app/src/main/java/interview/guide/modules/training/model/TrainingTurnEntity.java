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
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 一次向用户展示的问题及其回答、反馈和服务端内部评估。
 *
 * <p>回答在任务入队前先写入本实体。异步处理失败只改变 status 和 failureMessage，
 * 不清空 userAnswer，因此用户可以对同一个任务执行手动重试，不必重新输入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "training_turns",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_training_turn_turn_id", columnNames = "turn_id"),
        @UniqueConstraint(
            name = "uk_training_turn_session_index",
            columnNames = {"training_session_id", "turn_index"}
        )
    },
    indexes = {
        @Index(
            name = "idx_training_turn_session_status",
            columnList = "training_session_id,status"
        ),
        @Index(
            name = "idx_training_turn_session_topic",
            columnList = "training_session_id,topic_key,turn_index"
        )
    }
)
public class TrainingTurnEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "turn_id", nullable = false, length = 36, updatable = false)
  private String turnId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "training_session_id", nullable = false, updatable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private TrainingSessionEntity session;

  @Column(name = "turn_index", nullable = false, updatable = false)
  private Integer turnIndex;

  /**
   * 主问题编号。追问沿用父主问题编号，用于执行每个主问题最多两次追问的硬约束。
   */
  @Column(name = "main_question_index", nullable = false, updatable = false)
  private Integer mainQuestionIndex;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_turn_id", updatable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private TrainingTurnEntity parentTurn;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32, updatable = false)
  private TrainingAction action;

  @Column(name = "topic_key", nullable = false, length = 255, updatable = false)
  private String topicKey;

  @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
  private String question;

  @Column(name = "user_answer", columnDefinition = "TEXT")
  private String userAnswer;

  @Column(columnDefinition = "TEXT")
  private String feedback;

  /**
   * 仅供动作策略和最终总结使用的 0 到 100 数值，不通过轮次接口直接展示。
   */
  @Column(name = "internal_score")
  private Integer internalScore;

  @Column(name = "reference_answer", columnDefinition = "TEXT")
  private String referenceAnswer;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  @Builder.Default
  private TrainingTurnStatus status = TrainingTurnStatus.WAITING_ANSWER;

  /**
   * 只保存可展示的通用错误提示，不保存异常类名、堆栈或模型原始输出。
   */
  @Column(name = "failure_message", length = 500)
  private String failureMessage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "answered_at")
  private LocalDateTime answeredAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Version
  @Column(nullable = false)
  private Long version;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    if (status == null) {
      status = TrainingTurnStatus.WAITING_ANSWER;
    }
  }
}
