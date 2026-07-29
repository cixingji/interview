package interview.guide.modules.training.repository;

import interview.guide.modules.training.model.TrainingSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 训练会话数据访问。
 */
@Repository
public interface TrainingSessionRepository extends JpaRepository<TrainingSessionEntity, Long> {

  /**
   * 连同主题快照一次加载，供会话详情和后续动作策略使用，避免逐主题查询。
   */
  @EntityGraph(attributePaths = "topics")
  Optional<TrainingSessionEntity> findByTrainingId(String trainingId);
}
