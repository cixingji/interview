import type {
  TrainingSessionStatus,
  TrainingTaskPollResponse,
} from '../types/training';

export type TrainingPollTarget =
  | { kind: 'TASK'; taskId: string }
  | { kind: 'LATEST' }
  | null;

const SESSION_STATUS_RANK: Record<TrainingSessionStatus, number> = {
  READY: 0,
  IN_PROGRESS: 1,
  SUMMARIZING: 2,
  COMPLETED: 3,
  FAILED: 4,
};

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
  /*
   * 会话详情与任务接口是两个独立 HTTP 请求，可能恰好跨过一次状态提交。生命周期只会
   * 单向前进，因此选择阶段更靠后的状态，不能让较早返回的旧快照阻止总结任务轮询。
   */
  const effectiveSessionStatus = laterSessionStatus(
    sessionStatus,
    taskPoll?.sessionStatus,
  );
  if (
    effectiveSessionStatus === 'SUMMARIZING'
    && (!taskPoll || taskPoll.task.taskType !== 'SUMMARY')
  ) {
    return { kind: 'LATEST' };
  }
  return null;
}

function laterSessionStatus(
  first: TrainingSessionStatus | undefined,
  second: TrainingSessionStatus | undefined,
): TrainingSessionStatus | undefined {
  if (!first) return second;
  if (!second) return first;
  return SESSION_STATUS_RANK[first] >= SESSION_STATUS_RANK[second]
    ? first
    : second;
}
