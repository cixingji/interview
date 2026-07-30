import type {
  TrainingSessionStatus,
  TrainingTaskPollResponse,
} from '../types/training';

export type TrainingPollTarget =
  | { kind: 'TASK'; taskId: string }
  | { kind: 'LATEST' }
  | null;

/**
 * 根据服务端公开状态决定下一次轮询目标。
 *
 * 普通任务始终固定轮询 taskId，避免 latest 在并发创建新任务时跳走；只有回答任务已经
 * 完成、会话已进入 SUMMARIZING 且总结任务尚未出现时，才轮询 latest 等待 SUMMARY。
 * 已失败的 SUMMARY 是需要用户确认重试的终态，不能继续后台轮询。
 */
export function resolveTrainingPollTarget(
  sessionStatus: TrainingSessionStatus | undefined,
  taskPoll: TrainingTaskPollResponse | null,
): TrainingPollTarget {
  if (taskPoll && !taskPoll.terminal) {
    return { kind: 'TASK', taskId: taskPoll.task.taskId };
  }
  if (
    sessionStatus === 'SUMMARIZING'
    && (!taskPoll || taskPoll.task.taskType !== 'SUMMARY')
  ) {
    return { kind: 'LATEST' };
  }
  return null;
}
