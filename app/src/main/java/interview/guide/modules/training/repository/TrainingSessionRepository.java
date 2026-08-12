package interview.guide.modules.training.repository;

import interview.guide.modules.training.model.TrainingSessionEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 训练会话数据访问。
 */
@Repository
public interface TrainingSessionRepository extends JpaRepository<TrainingSessionEntity, Long> {

  boolean existsByTrainingId(String trainingId);

  /**
   * 连同主题快照一次加载，供会话详情和后续动作策略使用，避免逐主题查询。
   */
  @EntityGraph(attributePaths = "topics")
  Optional<TrainingSessionEntity> findByTrainingId(String trainingId);

  /**
   * 领取任务、提交回答和恢复任务时锁定会话，保证会话计数与轮次状态不会被并发请求交叉覆盖。
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM TrainingSessionEntity s WHERE s.trainingId = :trainingId")
  Optional<TrainingSessionEntity> findByTrainingIdForUpdate(
      @Param("trainingId") String trainingId
  );
}
