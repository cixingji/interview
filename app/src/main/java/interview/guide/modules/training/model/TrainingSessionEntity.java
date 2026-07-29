package interview.guide.modules.training.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * ReAct 弱项训练会话。
 *
 * <p>该实体只保存服务端可以信任的状态和限制。LLM 后续只能在服务端计算出的允许动作集合中
 * 选择动作，不能直接修改本实体，也不能自行扩大最大题数、追问次数或主题覆盖范围。
 *
 * <p>{@code sourceResumeId} 只是筛选历史证据的来源标识，不建立外键。训练主题已经复制了固定
 * 诊断快照，因此删除原简历或面试记录不应连带删除一场已经存在的训练。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "training_sessions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_training_session_training_id", columnNames = "training_id")
    },
    indexes = {
        @Index(
            name = "idx_training_session_source_created",
            columnList = "source_resume_id,source_skill_id,created_at"
        ),
        @Index(
            name = "idx_training_session_status_updated",
            columnList = "status,updated_at"
        )
    }
)
public class TrainingSessionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 暴露给 API 和 Redis 消息的稳定 ID。数据库自增主键永不进入 Prompt 或前端请求。
   */
  @Column(name = "training_id", nullable = false, length = 36, updatable = false)
  private String trainingId;

  @Column(name = "source_resume_id", updatable = false)
  private Long sourceResumeId;

  @Column(name = "source_skill_id", nullable = false, length = 64, updatable = false)
  private String sourceSkillId;

  /**
   * 创建时选定的 Provider。允许为空，后续 Runner 为空时使用 Registry 的默认 Provider。
   */
  @Column(name = "llm_provider", length = 64, updatable = false)
  private String llmProvider;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  @Builder.Default
  private TrainingSessionStatus status = TrainingSessionStatus.READY;

  /**
   * 以下四个限制在创建时从配置复制到会话，之后不随全局配置改变。
   * 这样服务重启或配置调整不会改变一场进行中训练的边界。
   */
  @Column(name = "max_questions", nullable = false, updatable = false)
  private Integer maxQuestions;

  @Column(
      name = "max_consecutive_questions_per_topic",
      nullable = false,
      updatable = false
  )
  private Integer maxConsecutiveQuestionsPerTopic;

  @Column(name = "max_follow_ups_per_main_question", nullable = false, updatable = false)
  private Integer maxFollowUpsPerMainQuestion;

  @Column(name = "minimum_topic_count", nullable = false, updatable = false)
  private Integer minimumTopicCount;

  @Column(name = "question_count", nullable = false)
  @Builder.Default
  private Integer questionCount = 0;

  @Column(name = "covered_topic_count", nullable = false)
  @Builder.Default
  private Integer coveredTopicCount = 0;

  @Column(name = "current_topic_key", length = 255)
  private String currentTopicKey;

  @Column(name = "current_main_question_index")
  private Integer currentMainQuestionIndex;

  @Column(name = "consecutive_topic_question_count", nullable = false)
  @Builder.Default
  private Integer consecutiveTopicQuestionCount = 0;

  @Column(name = "current_main_question_follow_up_count", nullable = false)
  @Builder.Default
  private Integer currentMainQuestionFollowUpCount = 0;

  @OneToMany(
      mappedBy = "session",
      cascade = CascadeType.ALL,
      orphanRemoval = true
  )
  @OrderBy("priorityRank ASC")
  @Builder.Default
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private List<TrainingTopicEntity> topics = new ArrayList<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  private LocalDateTime startedAt;

  private LocalDateTime completedAt;

  /**
   * 防止轮次提交、消费者恢复和用户重试同时覆盖彼此的状态。
   */
  @Version
  @Column(nullable = false)
  private Long version;

  @PrePersist
  protected void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
    if (status == null) {
      status = TrainingSessionStatus.READY;
    }
    if (questionCount == null) {
      questionCount = 0;
    }
    if (coveredTopicCount == null) {
      coveredTopicCount = 0;
    }
    if (consecutiveTopicQuestionCount == null) {
      consecutiveTopicQuestionCount = 0;
    }
    if (currentMainQuestionFollowUpCount == null) {
      currentMainQuestionFollowUpCount = 0;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  /**
   * 同时维护双向关系，确保级联保存时 topic 的外键始终指向当前会话。
   */
  public void addTopic(TrainingTopicEntity topic) {
    topics.add(topic);
    topic.setSession(this);
  }
}
