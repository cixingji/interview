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
import jakarta.persistence.PreUpdate;
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
 * 训练轮次的数据库任务记录。
 *
 * <p>Redis 消息只携带 taskId 和 trainingId。消费者必须重新读取本实体并通过条件更新领取，
 * 因此重复投递、pending reclaim 或多个实例同时消费都不会重复执行同一个 QUEUED 任务。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "training_tasks",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_training_task_task_id", columnNames = "task_id"),
        @UniqueConstraint(
            name = "uk_training_task_deduplication_key",
            columnNames = "deduplication_key"
        )
    },
    indexes = {
        @Index(
            name = "idx_training_task_status_updated",
            columnList = "status,updated_at"
        ),
        @Index(
            name = "idx_training_task_session_created",
            columnList = "training_session_id,created_at"
        )
    }
)
public class TrainingTaskEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_id", nullable = false, length = 36, updatable = false)
  private String taskId;

  @Column(name = "deduplication_key", nullable = false, length = 128, updatable = false)
  private String deduplicationKey;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "training_session_id", nullable = false, updatable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private TrainingSessionEntity session;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_turn_id", updatable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private TrainingTurnEntity sourceTurn;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 24, updatable = false)
  private TrainingTaskType taskType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  @Builder.Default
  private TrainingTaskStatus status = TrainingTaskStatus.QUEUED;

  /**
   * 每次成功从 QUEUED 原子领取时加一，用于诊断重复恢复和实际执行次数。
   */
  @Column(name = "attempt_count", nullable = false)
  @Builder.Default
  private Integer attemptCount = 0;

  /**
   * 当前自动重试周期的 Redis 重试次数。与累计 attemptCount 分开保存：
   * 宕机恢复不会把重试预算清零，用户手动重试时则可以明确开启一轮新预算。
   */
  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private Integer retryCount = 0;

  @Column(name = "safe_error_message", length = 500)
  private String safeErrorMessage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "processing_started_at")
  private LocalDateTime processingStartedAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Version
  @Column(nullable = false)
  private Long version;

  @PrePersist
  protected void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
    if (status == null) {
      status = TrainingTaskStatus.QUEUED;
    }
    if (attemptCount == null) {
      attemptCount = 0;
    }
    if (retryCount == null) {
      retryCount = 0;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
