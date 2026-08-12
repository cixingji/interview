export type TrainingSessionStatus =
  | 'READY'
  | 'IN_PROGRESS'
  | 'SUMMARIZING'
  | 'COMPLETED'
  | 'FAILED';

export type TrainingTopicStatus = 'PENDING' | 'ACTIVE' | 'COMPLETED';

export type TrainingTaskType = 'INITIAL_TURN' | 'ANSWER_TURN' | 'SUMMARY';

export type TrainingTaskStatus =
  | 'QUEUED'
  | 'ANALYZING'
  | 'RETRIEVING'
  | 'DECIDING'
  | 'GENERATING'
  | 'COMPLETED'
  | 'FAILED';

export type TrainingTurnStatus =
  | 'WAITING_ANSWER'
  | 'QUEUED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED';

export type TrainingAction =
  | 'FOLLOW_UP'
  | 'REINFORCE'
  | 'ASK_NEW_QUESTION'
  | 'SWITCH_TOPIC'
  | 'FINISH';

export interface TrainingTopic {
  topicKey: string;
  displayName: string;
  originalAverageScore: number;
  sampleCount: number;
  priorityRank: number;
  status: TrainingTopicStatus;
  questionCount: number;
}

export interface TrainingSession {
  trainingId: string;
  sourceResumeId: number | null;
  sourceSkillId: string;
  status: TrainingSessionStatus;
  maxQuestions: number;
  maxConsecutiveQuestionsPerTopic: number;
  maxFollowUpsPerMainQuestion: number;
  minimumTopicCount: number;
  questionCount: number;
  coveredTopicCount: number;
  topics: TrainingTopic[];
  createdAt: string;
  updatedAt: string;
}

export interface TrainingTask {
  taskId: string;
  trainingId: string;
  sourceTurnId: string | null;
  taskType: TrainingTaskType;
  status: TrainingTaskStatus;
  attemptCount: number;
  safeErrorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface TrainingTurn {
  turnId: string;
  turnIndex: number;
  mainQuestionIndex: number;
  action: TrainingAction;
  topicKey: string;
  question: string;
  userAnswer: string | null;
  feedback: string | null;
  referenceAnswer: string | null;
  status: TrainingTurnStatus;
  createdAt: string;
  answeredAt: string | null;
  completedAt: string | null;
}

export interface TrainingTaskPollResponse {
  task: TrainingTask;
  sessionStatus: TrainingSessionStatus;
  evaluatedTurn: TrainingTurn | null;
  nextTurn: TrainingTurn | null;
  terminal: boolean;
  retryable: boolean;
}

export interface TrainingSummaryTopic {
  topicKey: string;
  displayName: string;
  originalAverageScore: number;
  trainingAverageScore: number;
  answeredQuestionCount: number;
}

export interface TrainingSummary {
  trainingId: string;
  overallScore: number;
  completedQuestionCount: number;
  coveredTopicCount: number;
  narrative: string;
  strengths: string[];
  improvements: string[];
  nextSteps: string[];
  topics: TrainingSummaryTopic[];
  generatedAt: string;
}

export interface CreateTrainingSessionRequest {
  resumeId?: number;
  skillId: string;
  llmProvider?: string;
}

export interface SubmitTrainingAnswerRequest {
  answer: string;
}
