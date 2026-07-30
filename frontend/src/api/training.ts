import { request } from './request';
import type {
  CreateTrainingSessionRequest,
  SubmitTrainingAnswerRequest,
  TrainingSession,
  TrainingSummary,
  TrainingTask,
  TrainingTaskPollResponse,
  TrainingTurn,
} from '../types/training';

const sessionPath = (trainingId: string) =>
  `/api/training/sessions/${encodeURIComponent(trainingId)}`;

/**
 * ReAct 弱项训练接口。
 *
 * 所有耗时操作只返回异步任务，页面必须通过 pollTask 读取数据库中的可信状态，
 * 不能根据按钮点击时间自行推断任务已经完成。
 */
export const trainingApi = {
  createSession(data: CreateTrainingSessionRequest): Promise<TrainingSession> {
    return request.post<TrainingSession>('/api/training/sessions', data);
  },

  getSession(trainingId: string): Promise<TrainingSession> {
    return request.get<TrainingSession>(sessionPath(trainingId));
  },

  startTraining(trainingId: string): Promise<TrainingTask> {
    return request.post<TrainingTask>(`${sessionPath(trainingId)}/start`);
  },

  submitAnswer(
    trainingId: string,
    turnId: string,
    data: SubmitTrainingAnswerRequest,
  ): Promise<TrainingTask> {
    return request.post<TrainingTask>(
      `${sessionPath(trainingId)}/turns/${encodeURIComponent(turnId)}/answers`,
      data,
    );
  },

  pollTask(trainingId: string, taskId: string): Promise<TrainingTaskPollResponse> {
    return request.get<TrainingTaskPollResponse>(
      `${sessionPath(trainingId)}/tasks/${encodeURIComponent(taskId)}`,
    );
  },

  getLatestTask(trainingId: string): Promise<TrainingTaskPollResponse | null> {
    return request.get<TrainingTaskPollResponse | null>(
      `${sessionPath(trainingId)}/tasks/latest`,
    );
  },

  retryTask(trainingId: string, taskId: string): Promise<TrainingTask> {
    return request.post<TrainingTask>(
      `${sessionPath(trainingId)}/tasks/${encodeURIComponent(taskId)}/retry`,
    );
  },

  listTurns(trainingId: string): Promise<TrainingTurn[]> {
    return request.get<TrainingTurn[]>(`${sessionPath(trainingId)}/turns`);
  },

  getSummary(trainingId: string): Promise<TrainingSummary | null> {
    return request.get<TrainingSummary | null>(`${sessionPath(trainingId)}/summary`);
  },
};
