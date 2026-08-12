import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { trainingApi } from '../api/training';
import { getErrorMessage } from '../api/request';
import type {
  TrainingSession,
  TrainingSummary,
  TrainingTask,
  TrainingTaskPollResponse,
  TrainingTurn,
} from '../types/training';
import { resolveTrainingPollTarget } from './trainingPolling';

const POLL_INTERVAL_MS = 1500;

interface TrainingSessionState {
  session: TrainingSession | null;
  turns: TrainingTurn[];
  taskPoll: TrainingTaskPollResponse | null;
  summary: TrainingSummary | null;
}

const EMPTY_STATE: TrainingSessionState = {
  session: null,
  turns: [],
  taskPoll: null,
  summary: null,
};

/**
 * 管理单个训练会话的服务端状态和异步任务轮询。
 *
 * 页面刷新后只依赖 trainingId 恢复会话、轮次和最新任务，不把 React 内存状态当作
 * 业务事实。轮询使用递归 setTimeout：下一次请求只会在上一次请求结束后安排，
 * 从而避免慢网络下 setInterval 产生并发请求和旧响应覆盖新状态。
 */
export function useTrainingSession(trainingId: string | undefined) {
  const [state, setState] = useState<TrainingSessionState>(EMPTY_STATE);
  const [loading, setLoading] = useState(Boolean(trainingId));
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState('');
  const stateRequestVersionRef = useRef(0);

  const loadState = useCallback(async (silent = false) => {
    if (!trainingId) return;
    const requestVersion = ++stateRequestVersionRef.current;
    if (!silent) setLoading(true);

    try {
      let [session, turns] = await Promise.all([
        trainingApi.getSession(trainingId),
        trainingApi.listTurns(trainingId),
      ]);
      const taskPoll = await trainingApi.getLatestTask(trainingId);

      /*
       * 最新任务查询发生在首批会话/轮次之后。如果它观察到不同的会话阶段，说明首批请求
       * 跨过了一次原子状态提交；再读取一次会话和轮次，避免把两个时点的数据拼在一起。
       */
      if (taskPoll && taskPoll.sessionStatus !== session.status) {
        [session, turns] = await Promise.all([
          trainingApi.getSession(trainingId),
          trainingApi.listTurns(trainingId),
        ]);
      }
      const summary = session.status === 'COMPLETED'
        ? await trainingApi.getSummary(trainingId)
        : null;

      /*
       * 会话路由快速切换或多个刷新请求相邻发生时，只允许最后发起的请求更新页面。
       * Axios 请求即使无法立即取消，也不会再用旧会话响应覆盖新会话状态。
       */
      if (requestVersion !== stateRequestVersionRef.current) return;
      setState({ session, turns, taskPoll, summary });
      setError('');
    } catch (requestError) {
      if (requestVersion !== stateRequestVersionRef.current) return;
      setError(getErrorMessage(requestError));
    } finally {
      if (!silent && requestVersion === stateRequestVersionRef.current) {
        setLoading(false);
      }
    }
  }, [trainingId]);

  useEffect(() => {
    setState(EMPTY_STATE);
    if (!trainingId) {
      setLoading(false);
      return;
    }
    void loadState();
    return () => {
      stateRequestVersionRef.current += 1;
    };
  }, [loadState, trainingId]);

  const pollTarget = resolveTrainingPollTarget(
    state.session?.status,
    state.taskPoll,
  );
  const pollTargetKind = pollTarget?.kind;
  const pollTargetTaskId = pollTarget?.kind === 'TASK'
    ? pollTarget.taskId
    : null;

  useEffect(() => {
    if (!trainingId || !pollTargetKind) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const scheduleNext = () => {
      if (!cancelled) {
        timer = setTimeout(poll, POLL_INTERVAL_MS);
      }
    };

    const poll = async () => {
      try {
        /*
         * 普通任务处理中固定轮询当前 taskId；当回答任务已经结束而会话进入总结阶段时，
         * 改查 latest，让异步创建的 SUMMARY 任务出现后能被接管，不会卡在旧任务上。
         */
        const nextPoll = pollTargetKind === 'TASK'
          ? await trainingApi.pollTask(trainingId, pollTargetTaskId!)
          : await trainingApi.getLatestTask(trainingId);

        if (cancelled) return;
        if (nextPoll) {
          setState(current => ({ ...current, taskPoll: nextPoll }));
        }

        const shouldRefreshSnapshot = nextPoll?.terminal
          || nextPoll?.sessionStatus !== state.session?.status;
        if (shouldRefreshSnapshot) {
          await loadState(true);
          if (cancelled) return;
        } else {
          setError('');
        }
      } catch (requestError) {
        if (!cancelled) {
          setError(`获取训练进度失败：${getErrorMessage(requestError)}`);
        }
      }

      scheduleNext();
    };

    timer = setTimeout(poll, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [
    loadState,
    pollTargetKind,
    pollTargetTaskId,
    state.session?.status,
    trainingId,
  ]);

  const adoptTask = useCallback(async (task: TrainingTask) => {
    if (!trainingId) return;
    const taskPoll = await trainingApi.pollTask(trainingId, task.taskId);
    setState(current => ({ ...current, taskPoll }));
    await loadState(true);
  }, [loadState, trainingId]);

  const runAction = useCallback(async (action: () => Promise<TrainingTask>) => {
    setActionLoading(true);
    setError('');
    try {
      const task = await action();
      await adoptTask(task);
    } catch (requestError) {
      /*
       * POST 可能已经在服务端成功提交，只是响应在网络中断时丢失。先恢复服务端最新状态，
       * 再向用户报错，能阻止页面继续展示可提交表单而造成重复回答。
       */
      await loadState(true);
      setError(getErrorMessage(requestError));
      throw requestError;
    } finally {
      setActionLoading(false);
    }
  }, [adoptTask, loadState]);

  const startTraining = useCallback(
    () => trainingId
      ? runAction(() => trainingApi.startTraining(trainingId))
      : Promise.resolve(),
    [runAction, trainingId],
  );

  const submitAnswer = useCallback(
    (turnId: string, answer: string) => trainingId
      ? runAction(() => trainingApi.submitAnswer(trainingId, turnId, { answer }))
      : Promise.resolve(),
    [runAction, trainingId],
  );

  const retryTask = useCallback(
    () => trainingId && state.taskPoll?.retryable
      ? runAction(() => trainingApi.retryTask(trainingId, state.taskPoll!.task.taskId))
      : Promise.resolve(),
    [runAction, state.taskPoll, trainingId],
  );

  const activeTurn = useMemo(
    () => [...state.turns].reverse().find(turn => turn.status === 'WAITING_ANSWER') ?? null,
    [state.turns],
  );

  return {
    ...state,
    activeTurn,
    loading,
    actionLoading,
    error,
    reload: loadState,
    startTraining,
    submitAnswer,
    retryTask,
  };
}
