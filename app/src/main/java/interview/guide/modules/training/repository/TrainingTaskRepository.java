package interview.guide.modules.training.repository;

import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 训练异步任务数据访问。
 *
 * <p>领取与进度推进使用带期望状态的条件更新。即使 Redis 重复投递，只有第一个把
 * QUEUED 改为 ANALYZING 的消费者能够执行耗时业务。
 */
@Repository
public interface TrainingTaskRepository extends JpaRepository<TrainingTaskEntity, Long> {

  @EntityGraph(attributePaths = {"session", "sourceTurn"})
  Optional<TrainingTaskEntity> findByTaskId(String taskId);

  @EntityGraph(attributePaths = {"session", "sourceTurn"})
  Optional<TrainingTaskEntity> findByDeduplicationKey(String deduplicationKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      SELECT t
      FROM TrainingTaskEntity t
      JOIN FETCH t.session
      LEFT JOIN FETCH t.sourceTurn
      WHERE t.taskId = :taskId
      """)
  Optional<TrainingTaskEntity> findByTaskIdForUpdate(@Param("taskId") String taskId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE TrainingTaskEntity t
      SET t.status = :claimedStatus,
          t.attemptCount = t.attemptCount + 1,
          t.processingStartedAt = :now,
          t.safeErrorMessage = NULL,
          t.updatedAt = :now,
          t.version = t.version + 1
      WHERE t.taskId = :taskId
        AND t.session.trainingId = :trainingId
        AND t.status = :queuedStatus
      """)
  int claimQueuedTask(
      @Param("taskId") String taskId,
      @Param("trainingId") String trainingId,
      @Param("queuedStatus") TrainingTaskStatus queuedStatus,
      @Param("claimedStatus") TrainingTaskStatus claimedStatus,
      @Param("now") LocalDateTime now
  );

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE TrainingTaskEntity t
      SET t.status = :nextStatus,
          t.updatedAt = :now,
          t.version = t.version + 1
      WHERE t.taskId = :taskId
        AND t.status = :expectedStatus
      """)
  int advanceStatus(
      @Param("taskId") String taskId,
      @Param("expectedStatus") TrainingTaskStatus expectedStatus,
      @Param("nextStatus") TrainingTaskStatus nextStatus,
      @Param("now") LocalDateTime now
  );

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE TrainingTaskEntity t
      SET t.updatedAt = :now,
          t.version = t.version + 1
      WHERE t.taskId = :taskId
        AND t.status IN :processingStatuses
      """)
  int touchProcessing(
      @Param("taskId") String taskId,
      @Param("processingStatuses") Collection<TrainingTaskStatus> processingStatuses,
      @Param("now") LocalDateTime now
  );

  /**
   * 恢复调度只读取有限数量的轻量候选 ID；真正改状态时仍会逐条加锁并再次核对时间。
   */
  @Query("""
      SELECT t.taskId
      FROM TrainingTaskEntity t
      WHERE t.status IN :statuses
        AND t.updatedAt < :threshold
      ORDER BY t.updatedAt ASC
      """)
  List<String> findStaleTaskIds(
      @Param("statuses") Collection<TrainingTaskStatus> statuses,
      @Param("threshold") LocalDateTime threshold,
      Pageable pageable
  );
}
