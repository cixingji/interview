package interview.guide.modules.training.model;

/**
 * ReAct Runner 最终可以执行的训练动作。
 *
 * <p>枚举现在进入持久化模型，第四部分会由服务端根据会话状态计算 allowedActions，
 * 再校验模型选择是否属于该集合。模型不能通过输出任意字符串扩展动作权限。
 */
public enum TrainingAction {
  FOLLOW_UP,
  REINFORCE,
  ASK_NEW_QUESTION,
  SWITCH_TOPIC,
  FINISH
}
