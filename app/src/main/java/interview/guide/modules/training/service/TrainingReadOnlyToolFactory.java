package interview.guide.modules.training.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.training.model.TrainingExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 为每个任务创建独立工具对象，避免在单例 Bean 中保存会话状态。
 */
@Component
@RequiredArgsConstructor
public class TrainingReadOnlyToolFactory {

  private final InterviewSkillService skillService;
  private final PromptSanitizer promptSanitizer;

  public TrainingReadOnlyTools create(TrainingExecutionContext context) {
    String reference = skillService.buildEvaluationReferenceSectionSafe(context.skillId());
    return new TrainingReadOnlyTools(context, reference, promptSanitizer);
  }
}
