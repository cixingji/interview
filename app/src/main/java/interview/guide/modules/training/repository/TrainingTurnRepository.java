package interview.guide.modules.training.repository;

import interview.guide.modules.training.model.TrainingTurnEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 训练轮次数据访问。
 */
@Repository
public interface TrainingTurnRepository extends JpaRepository<TrainingTurnEntity, Long> {

  @EntityGraph(attributePaths = {"session", "parentTurn"})
  Optional<TrainingTurnEntity> findByTurnId(String turnId);

  List<TrainingTurnEntity> findBySession_TrainingIdOrderByTurnIndexAsc(String trainingId);

  Optional<TrainingTurnEntity> findBySession_TrainingIdAndTurnIndex(
      String trainingId,
      Integer turnIndex
  );

  /**
   * 提交回答或手动重试前锁定目标轮次。查询同时限定 trainingId，防止跨会话猜测 turnId。
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      SELECT t
      FROM TrainingTurnEntity t
      WHERE t.session.trainingId = :trainingId
        AND t.turnId = :turnId
      """)
  Optional<TrainingTurnEntity> findByTrainingIdAndTurnIdForUpdate(
      @Param("trainingId") String trainingId,
      @Param("turnId") String turnId
  );
}
