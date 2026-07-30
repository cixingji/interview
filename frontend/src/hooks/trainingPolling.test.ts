import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveTrainingPollTarget } from './trainingPolling.ts';
import type { TrainingTaskPollResponse } from '../types/training.ts';

function taskPoll(
  overrides: Partial<TrainingTaskPollResponse> = {},
): TrainingTaskPollResponse {
  return {
    task: {
      taskId: 'task-1',
      trainingId: 'training-1',
      sourceTurnId: null,
      taskType: 'ANSWER_TURN',
      status: 'QUEUED',
      attemptCount: 0,
      safeErrorMessage: null,
      createdAt: '2026-07-30T10:00:00',
      updatedAt: '2026-07-30T10:00:00',
      completedAt: null,
    },
    sessionStatus: 'IN_PROGRESS',
    evaluatedTurn: null,
    nextTurn: null,
    terminal: false,
    retryable: false,
    ...overrides,
  };
}

test('非终态任务固定轮询当前 taskId', () => {
  assert.deepEqual(
    resolveTrainingPollTarget('IN_PROGRESS', taskPoll()),
    { kind: 'TASK', taskId: 'task-1' },
  );
});

test('进入总结阶段但 SUMMARY 尚未出现时查询 latest', () => {
  const completedAnswer = taskPoll({
    task: {
      ...taskPoll().task,
      status: 'COMPLETED',
      completedAt: '2026-07-30T10:01:00',
    },
    sessionStatus: 'SUMMARIZING',
    terminal: true,
  });

  assert.deepEqual(
    resolveTrainingPollTarget('SUMMARIZING', completedAnswer),
    { kind: 'LATEST' },
  );
  assert.deepEqual(
    resolveTrainingPollTarget('SUMMARIZING', null),
    { kind: 'LATEST' },
  );
});

test('并行恢复拿到旧会话和新任务时按较新的总结状态继续轮询', () => {
  const completedAnswer = taskPoll({
    task: {
      ...taskPoll().task,
      status: 'COMPLETED',
      completedAt: '2026-07-30T10:01:00',
    },
    sessionStatus: 'SUMMARIZING',
    terminal: true,
  });

  assert.deepEqual(
    resolveTrainingPollTarget('IN_PROGRESS', completedAnswer),
    { kind: 'LATEST' },
  );
});

test('失败的 SUMMARY 停止轮询并等待用户触发重试', () => {
  const failedSummary = taskPoll({
    task: {
      ...taskPoll().task,
      taskType: 'SUMMARY',
      status: 'FAILED',
      safeErrorMessage: '总结暂时失败',
    },
    sessionStatus: 'SUMMARIZING',
    terminal: true,
    retryable: true,
  });

  assert.equal(resolveTrainingPollTarget('SUMMARIZING', failedSummary), null);
});

test('会话完成后不再轮询已完成的总结任务', () => {
  const completedSummary = taskPoll({
    task: {
      ...taskPoll().task,
      taskType: 'SUMMARY',
      status: 'COMPLETED',
      completedAt: '2026-07-30T10:02:00',
    },
    sessionStatus: 'COMPLETED',
    terminal: true,
  });

  assert.equal(resolveTrainingPollTarget('COMPLETED', completedSummary), null);
});
