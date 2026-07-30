package interview.guide.modules.training.repository;

import interview.guide.modules.training.model.TrainingSummaryEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 训练总结快照数据访问。
 */
@Repository
public interface TrainingSummaryRepository
    extends JpaRepository<TrainingSummaryEntity, Long> {

  @EntityGraph(attributePaths = "session")
  Optional<TrainingSummaryEntity> findBySession_TrainingId(String trainingId);

  boolean existsBySession_TrainingId(String trainingId);
}
