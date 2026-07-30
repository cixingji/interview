package interview.guide.modules.training.repository;

import interview.guide.modules.training.model.TrainingTopicEntity;
import interview.guide.modules.training.model.TrainingTopicStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 训练主题快照数据访问。
 */
@Repository
public interface TrainingTopicRepository extends JpaRepository<TrainingTopicEntity, Long> {

  List<TrainingTopicEntity> findBySession_TrainingIdOrderByPriorityRankAsc(String trainingId);

  List<TrainingTopicEntity> findBySession_TrainingIdAndStatusOrderByPriorityRankAsc(
      String trainingId,
      TrainingTopicStatus status
  );

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      SELECT t
      FROM TrainingTopicEntity t
      WHERE t.session.trainingId = :trainingId
      ORDER BY t.priorityRank ASC
      """)
  List<TrainingTopicEntity> findByTrainingIdForUpdate(
      @Param("trainingId") String trainingId
  );
}
