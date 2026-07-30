package interview.guide.modules.training.service;

import interview.guide.modules.training.model.TrainingTaskDTO;
import interview.guide.modules.training.model.TrainingTaskEntity;
import interview.guide.modules.training.model.TrainingTurnEntity;
import interview.guide.modules.training.model.TrainingTurnResponse;
import org.springframework.stereotype.Component;

/**
 * 训练实体到安全对外视图的统一映射器。
 *
 * <p>映射逻辑集中在这里可以防止新接口误把 internalScore、failureMessage、数据库主键
 * 或整个 Entity 序列化给前端。调用方必须在事务内完成映射，不能把懒加载实体带出事务。
 */
@Component
public class TrainingResponseMapper {

  public TrainingTaskDTO toTaskDTO(TrainingTaskEntity task) {
    return new TrainingTaskDTO(
        task.getTaskId(),
        task.getSession().getTrainingId(),
        task.getSourceTurn() == null ? null : task.getSourceTurn().getTurnId(),
        task.getTaskType(),
        task.getStatus(),
        task.getAttemptCount(),
        task.getSafeErrorMessage(),
        task.getCreatedAt(),
        task.getUpdatedAt(),
        task.getCompletedAt()
    );
  }

  public TrainingTurnResponse toTurnResponse(TrainingTurnEntity turn) {
    return new TrainingTurnResponse(
        turn.getTurnId(),
        turn.getTurnIndex(),
        turn.getMainQuestionIndex(),
        turn.getAction(),
        turn.getTopicKey(),
        turn.getQuestion(),
        turn.getUserAnswer(),
        turn.getFeedback(),
        turn.getReferenceAnswer(),
        turn.getStatus(),
        turn.getCreatedAt(),
        turn.getAnsweredAt(),
        turn.getCompletedAt()
    );
  }
}
