import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  AlertCircle,
  ArrowLeft,
  BookOpenCheck,
  Check,
  CheckCircle2,
  ChevronRight,
  Circle,
  Clock3,
  FileText,
  Loader2,
  RefreshCw,
  RotateCcw,
  Send,
  Sparkles,
  Target,
  TrendingUp,
  type LucideIcon,
} from 'lucide-react';
import { historyApi, type ResumeListItem } from '../api/history';
import { llmProviderApi } from '../api/llmProvider';
import { getErrorMessage } from '../api/request';
import { skillApi, type SkillDTO } from '../api/skill';
import { trainingApi } from '../api/training';
import { ROUTES } from '../constants/routes';
import { useTrainingSession } from '../hooks/useTrainingSession';
import type { ProviderItem } from '../types/llmProvider';
import type {
  TrainingAction,
  TrainingSession,
  TrainingSummary,
  TrainingTaskStatus,
  TrainingTopic,
  TrainingTurn,
} from '../types/training';
import { formatDateTime } from '../utils/date';

const TASK_STATUS_TEXT: Record<TrainingTaskStatus, string> = {
  QUEUED: '任务已排队',
  ANALYZING: '正在分析本轮回答',
  RETRIEVING: '正在检索训练依据',
  DECIDING: '正在规划下一步训练',
  GENERATING: '正在生成题目与反馈',
  COMPLETED: '任务已完成',
  FAILED: '任务处理失败',
};

const ACTION_TEXT: Record<TrainingAction, string> = {
  FOLLOW_UP: '追问',
  REINFORCE: '巩固',
  ASK_NEW_QUESTION: '新题',
  SWITCH_TOPIC: '切换主题',
  FINISH: '完成',
};

function TrainingSetup() {
  const navigate = useNavigate();
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [skillId, setSkillId] = useState('');
  const [resumeId, setResumeId] = useState('');
  const [providerId, setProviderId] = useState('');
  const [loadingOptions, setLoadingOptions] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    const loadOptions = async () => {
      setLoadingOptions(true);
      try {
        /*
         * 技能是创建训练的必要数据，因此读取失败时阻止提交；简历和供应商都是可选项，
         * 对应接口不可用时仍允许用户按技能和系统默认模型创建训练。
         */
        const [skillItems, resumeItems, providerItems] = await Promise.all([
          skillApi.listSkills(),
          historyApi.getResumes().catch(() => [] as ResumeListItem[]),
          llmProviderApi.list().catch(() => [] as ProviderItem[]),
        ]);
        if (cancelled) return;

        setSkills(skillItems);
        setResumes(resumeItems);
        setProviders(providerItems);
        setSkillId(
          skillItems.find(item => item.id === 'java-backend')?.id
            ?? skillItems[0]?.id
            ?? '',
        );
      } catch (requestError) {
        if (!cancelled) setError(getErrorMessage(requestError));
      } finally {
        if (!cancelled) setLoadingOptions(false);
      }
    };

    void loadOptions();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleCreate = async () => {
    if (!skillId || creating) return;
    setCreating(true);
    setError('');

    try {
      const session = await trainingApi.createSession({
        skillId,
        resumeId: resumeId ? Number(resumeId) : undefined,
        llmProvider: providerId || undefined,
      });
      navigate(`${ROUTES.training}/${session.trainingId}`);
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="ReAct 弱项训练"
        subtitle="根据已评估的历史回答定位薄弱主题，进行有界追问与强化练习"
      />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <h2 className="text-lg font-semibold text-slate-900 dark:text-white">创建训练</h2>

          {error && <ErrorBanner message={error} className="mt-4" />}

          <div className="mt-6 space-y-5">
            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-200">
                训练方向
              </span>
              <select
                value={skillId}
                onChange={event => setSkillId(event.target.value)}
                disabled={loadingOptions || creating}
                className="dark-input w-full rounded-lg px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-60"
              >
                {loadingOptions && <option value="">正在加载训练方向...</option>}
                {!loadingOptions && skills.length === 0 && <option value="">暂无可用训练方向</option>}
                {skills.map(skill => (
                  <option key={skill.id} value={skill.id}>{skill.name}</option>
                ))}
              </select>
            </label>

            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-200">
                历史简历范围
                <span className="ml-2 font-normal text-slate-400">可选</span>
              </span>
              <select
                value={resumeId}
                onChange={event => setResumeId(event.target.value)}
                disabled={loadingOptions || creating}
                className="dark-input w-full rounded-lg px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-60"
              >
                <option value="">不限简历，汇总该方向的面试历史</option>
                {resumes.map(resume => (
                  <option key={resume.id} value={resume.id}>{resume.filename}</option>
                ))}
              </select>
            </label>

            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-200">
                模型供应商
                <span className="ml-2 font-normal text-slate-400">可选</span>
              </span>
              <select
                value={providerId}
                onChange={event => setProviderId(event.target.value)}
                disabled={loadingOptions || creating}
                className="dark-input w-full rounded-lg px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-60"
              >
                <option value="">使用系统默认模型</option>
                {providers.map(provider => (
                  <option key={provider.id} value={provider.id}>
                    {provider.id} · {provider.model}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <button
            type="button"
            onClick={() => void handleCreate()}
            disabled={loadingOptions || creating || !skillId}
            className="mt-7 flex w-full items-center justify-center gap-2 rounded-lg bg-primary-600 px-5 py-3 text-sm font-semibold text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-700"
          >
            {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Target className="h-4 w-4" />}
            {creating ? '正在创建训练...' : '分析弱项并创建训练'}
          </button>
        </section>

        <aside className="border-l border-slate-200 pl-6 dark:border-slate-700">
          <h2 className="text-sm font-semibold text-slate-900 dark:text-white">开始条件</h2>
          <div className="mt-4 space-y-4">
            <Requirement icon={CheckCircle2} text="历史面试已经完成整体评估" />
            <Requirement icon={FileText} text="至少一个分类有两条有效回答" />
            <Requirement icon={Sparkles} text="已配置可用的聊天模型" />
          </div>
          <p className="mt-6 text-xs leading-5 text-slate-500 dark:text-slate-400">
            训练只读取所选范围内的历史证据。创建后会固化诊断快照，后续历史记录变化不会改变本次训练基线。
          </p>
        </aside>
      </div>
    </div>
  );
}

function TrainingWorkspace({ trainingId }: { trainingId: string }) {
  const navigate = useNavigate();
  const {
    session,
    turns,
    taskPoll,
    summary,
    activeTurn,
    loading,
    actionLoading,
    error,
    reload,
    startTraining,
    submitAnswer,
    retryTask,
  } = useTrainingSession(trainingId);
  const [answer, setAnswer] = useState('');
  const currentTurn = activeTurn
    ?? (taskPoll?.nextTurn?.status === 'WAITING_ANSWER' ? taskPoll.nextTurn : null);

  useEffect(() => {
    setAnswer('');
  }, [currentTurn?.turnId]);

  const completedTurns = useMemo(
    () => turns.filter(turn => turn.status === 'COMPLETED'),
    [turns],
  );
  const taskRunning = Boolean(taskPoll && !taskPoll.terminal);

  const handleAnswer = async () => {
    const normalizedAnswer = answer.trim();
    if (!currentTurn || !normalizedAnswer || actionLoading || taskRunning) return;
    try {
      await submitAnswer(currentTurn.turnId, normalizedAnswer);
      setAnswer('');
    } catch {
      // Hook 已恢复服务端最新状态并提供用户可见错误，页面无需重复处理。
    }
  };

  if (loading) {
    return <CenteredState icon={Loader2} title="正在恢复训练" spinning />;
  }

  if (!session) {
    return (
      <CenteredState
        icon={AlertCircle}
        title="无法读取训练"
        detail={error || '训练会话不存在或暂时不可用'}
        actionLabel="重新加载"
        onAction={() => void reload()}
      />
    );
  }

  return (
    <div className="mx-auto max-w-6xl">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          <button
            type="button"
            onClick={() => navigate(ROUTES.training)}
            title="返回新建训练"
            className="mt-0.5 flex h-9 w-9 flex-none items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-200 hover:text-slate-900 dark:hover:bg-slate-700 dark:hover:text-white"
          >
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="min-w-0">
            <h1 className="text-2xl font-bold text-slate-900 dark:text-white">ReAct 弱项训练</h1>
            <p className="mt-1 truncate text-sm text-slate-500 dark:text-slate-400">
              {session.sourceSkillId} · 创建于 {formatDateTime(session.createdAt)}
            </p>
          </div>
        </div>
        <SessionStatus status={session.status} />
      </div>

      {error && <ErrorBanner message={error} className="mb-5" />}

      <div className="grid gap-6 lg:grid-cols-[260px_minmax(0,1fr)]">
        <TrainingProgress session={session} />

        <main className="min-w-0">
          {session.status === 'READY' && (
            <ReadyPanel
              questionLimit={session.maxQuestions}
              loading={actionLoading}
              onStart={() => void startTraining()}
            />
          )}

          {session.status === 'IN_PROGRESS' && (
            <div className="space-y-5">
              {taskRunning && taskPoll && <TaskProgress status={taskPoll.task.status} />}

              {taskPoll?.task.status === 'FAILED' && (
                <TaskFailure
                  message={taskPoll.task.safeErrorMessage}
                  retryable={taskPoll.retryable}
                  loading={actionLoading}
                  onRetry={() => void retryTask()}
                />
              )}

              {currentTurn && (
                <AnswerPanel
                  turn={currentTurn}
                  answer={answer}
                  disabled={actionLoading || taskRunning}
                  onAnswerChange={setAnswer}
                  onSubmit={() => void handleAnswer()}
                />
              )}

              {!currentTurn && !taskRunning && taskPoll?.task.status !== 'FAILED' && (
                <CenteredState
                  icon={Clock3}
                  title="正在准备下一步"
                  detail="服务端正在衔接下一道题，请稍候。"
                />
              )}

              {completedTurns.length > 0 && <TurnHistory turns={completedTurns} />}
            </div>
          )}

          {session.status === 'SUMMARIZING' && (
            <div className="space-y-5">
              {taskPoll?.task.status === 'FAILED' ? (
                <TaskFailure
                  message={taskPoll.task.safeErrorMessage}
                  retryable={taskPoll.retryable}
                  loading={actionLoading}
                  onRetry={() => void retryTask()}
                />
              ) : (
                <CenteredState
                  icon={Loader2}
                  title="正在生成训练总结"
                  detail="回答已经保存，系统正在汇总各主题表现和后续训练建议。"
                  spinning
                />
              )}
              {completedTurns.length > 0 && <TurnHistory turns={completedTurns} />}
            </div>
          )}

          {session.status === 'COMPLETED' && (
            summary
              ? <SummaryReport summary={summary} />
              : (
                <CenteredState
                  icon={RefreshCw}
                  title="总结暂时不可用"
                  detail="训练已经完成，但总结尚未读取成功。"
                  actionLabel="重新加载"
                  onAction={() => void reload()}
                />
              )
          )}

          {session.status === 'FAILED' && (
            <CenteredState
              icon={AlertCircle}
              title="训练已终止"
              detail={taskPoll?.task.safeErrorMessage || '训练会话无法继续，请重新创建训练。'}
              actionLabel="新建训练"
              onAction={() => navigate(ROUTES.training)}
            />
          )}
        </main>
      </div>
    </div>
  );
}

function PageHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <header className="mb-7">
      <div className="flex items-center gap-3">
        <Target className="h-7 w-7 text-primary-600 dark:text-primary-400" />
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">{title}</h1>
      </div>
      <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>
    </header>
  );
}

function Requirement({
  icon: Icon,
  text,
}: {
  icon: LucideIcon;
  text: string;
}) {
  return (
    <div className="flex items-start gap-3 text-sm text-slate-600 dark:text-slate-300">
      <Icon className="mt-0.5 h-4 w-4 flex-none text-emerald-500" />
      <span>{text}</span>
    </div>
  );
}

function TrainingProgress({
  session,
}: {
  session: TrainingSession;
}) {
  const progress = session.maxQuestions === 0
    ? 0
    : Math.min(100, Math.round((session.questionCount / session.maxQuestions) * 100));

  return (
    <aside className="h-fit border-r border-slate-200 pr-6 dark:border-slate-700">
      <div className="flex items-end justify-between">
        <h2 className="text-sm font-semibold text-slate-900 dark:text-white">训练进度</h2>
        <span className="text-xs text-slate-500">{session.questionCount}/{session.maxQuestions} 题</span>
      </div>
      <div className="mt-3 h-2 overflow-hidden rounded bg-slate-200 dark:bg-slate-700">
        <div
          className="h-full bg-primary-600 transition-[width] duration-300"
          style={{ width: `${progress}%` }}
        />
      </div>

      <div className="mt-6 space-y-1">
        {session.topics.map(topic => <TopicRow key={topic.topicKey} topic={topic} />)}
      </div>
    </aside>
  );
}

function TopicRow({ topic }: { topic: TrainingTopic }) {
  const Icon = topic.status === 'COMPLETED'
    ? CheckCircle2
    : topic.status === 'ACTIVE'
      ? ChevronRight
      : Circle;
  const active = topic.status === 'ACTIVE';

  return (
    <div className={`flex items-start gap-2 rounded-lg px-2 py-2.5 ${active ? 'bg-primary-50 dark:bg-primary-950/40' : ''}`}>
      <Icon className={`mt-0.5 h-4 w-4 flex-none ${active ? 'text-primary-600 dark:text-primary-400' : 'text-slate-400'}`} />
      <div className="min-w-0 flex-1">
        <p className={`truncate text-sm ${active ? 'font-semibold text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
          {topic.displayName}
        </p>
        <p className="mt-0.5 text-xs text-slate-400">
          历史 {Math.round(topic.originalAverageScore)} 分 · {topic.sampleCount} 条证据
        </p>
      </div>
    </div>
  );
}

function ReadyPanel({
  questionLimit,
  loading,
  onStart,
}: {
  questionLimit: number;
  loading: boolean;
  onStart: () => void;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-7 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <BookOpenCheck className="h-10 w-10 text-primary-600 dark:text-primary-400" />
      <h2 className="mt-5 text-xl font-semibold text-slate-900 dark:text-white">弱项快照已准备</h2>
      <p className="mt-2 max-w-xl text-sm leading-6 text-slate-500 dark:text-slate-400">
        本次最多进行 {questionLimit} 道题。系统会根据回答选择追问、巩固或切换主题，并在达到边界后自动生成总结。
      </p>
      <button
        type="button"
        onClick={onStart}
        disabled={loading}
        className="mt-6 inline-flex items-center gap-2 rounded-lg bg-primary-600 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
        {loading ? '正在启动...' : '开始训练'}
      </button>
    </section>
  );
}

function TaskProgress({ status }: { status: TrainingTaskStatus }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-primary-200 bg-primary-50 px-4 py-3 text-sm text-primary-800 dark:border-primary-800 dark:bg-primary-950/40 dark:text-primary-200">
      <Loader2 className="h-4 w-4 flex-none animate-spin" />
      <div>
        <p className="font-medium">{TASK_STATUS_TEXT[status]}</p>
        <p className="mt-0.5 text-xs text-primary-600 dark:text-primary-400">完成后页面会自动更新</p>
      </div>
    </div>
  );
}

function TaskFailure({
  message,
  retryable,
  loading,
  onRetry,
}: {
  message: string | null;
  retryable: boolean;
  loading: boolean;
  onRetry: () => void;
}) {
  return (
    <section className="rounded-lg border border-red-200 bg-red-50 p-5 dark:border-red-900 dark:bg-red-950/30">
      <div className="flex items-start gap-3">
        <AlertCircle className="mt-0.5 h-5 w-5 flex-none text-red-500" />
        <div className="min-w-0 flex-1">
          <h2 className="font-semibold text-red-800 dark:text-red-200">本次处理失败</h2>
          <p className="mt-1 text-sm text-red-700 dark:text-red-300">
            {message || '训练任务暂时无法完成'}
          </p>
          {retryable && (
            <button
              type="button"
              onClick={onRetry}
              disabled={loading}
              className="mt-4 inline-flex items-center gap-2 rounded-lg border border-red-300 px-4 py-2 text-sm font-medium text-red-700 transition-colors hover:bg-red-100 disabled:opacity-60 dark:border-red-800 dark:text-red-300 dark:hover:bg-red-950"
            >
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
              重新处理
            </button>
          )}
        </div>
      </div>
    </section>
  );
}

function AnswerPanel({
  turn,
  answer,
  disabled,
  onAnswerChange,
  onSubmit,
}: {
  turn: TrainingTurn;
  answer: string;
  disabled: boolean;
  onAnswerChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const canSubmit = answer.trim().length > 0 && !disabled;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-800">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="rounded bg-primary-100 px-2 py-1 text-xs font-semibold text-primary-700 dark:bg-primary-900/50 dark:text-primary-300">
            第 {turn.turnIndex} 题
          </span>
          <span className="text-xs text-slate-500">{ACTION_TEXT[turn.action]}</span>
        </div>
        <span className="text-xs font-medium text-slate-500">{turn.topicKey}</span>
      </div>

      <h2 className="mt-5 text-lg font-semibold leading-7 text-slate-900 dark:text-white">
        {turn.question}
      </h2>

      <label className="mt-6 block">
        <span className="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-200">你的回答</span>
        <textarea
          value={answer}
          onChange={event => onAnswerChange(event.target.value)}
          disabled={disabled}
          maxLength={8000}
          rows={8}
          placeholder="结合原理、实际场景和取舍来回答..."
          className="dark-input min-h-44 w-full resize-y rounded-lg px-4 py-3 text-sm leading-6 disabled:cursor-not-allowed disabled:opacity-60"
        />
      </label>

      <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
        <span className="text-xs text-slate-400">{answer.length}/8000</span>
        <button
          type="button"
          onClick={onSubmit}
          disabled={!canSubmit}
          className="inline-flex items-center gap-2 rounded-lg bg-primary-600 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-700"
        >
          {disabled ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
          提交回答
        </button>
      </div>
    </section>
  );
}

function TurnHistory({ turns }: { turns: TrainingTurn[] }) {
  return (
    <section>
      <h2 className="mb-3 text-sm font-semibold text-slate-900 dark:text-white">已完成题目</h2>
      <div className="space-y-3">
        {[...turns].reverse().map((turn, index) => (
          <details
            key={turn.turnId}
            open={index === 0}
            className="group rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800"
          >
            <summary className="flex cursor-pointer list-none items-start gap-3">
              <Check className="mt-0.5 h-4 w-4 flex-none text-emerald-500" />
              <span className="min-w-0 flex-1 text-sm font-medium leading-5 text-slate-800 dark:text-slate-100">
                {turn.question}
              </span>
              <ChevronRight className="h-4 w-4 flex-none text-slate-400 transition-transform group-open:rotate-90" />
            </summary>
            <div className="mt-4 space-y-4 border-t border-slate-100 pt-4 text-sm dark:border-slate-700">
              <FeedbackBlock title="你的回答" content={turn.userAnswer} />
              <FeedbackBlock title="本题反馈" content={turn.feedback} emphasis />
              <FeedbackBlock title="参考回答" content={turn.referenceAnswer} />
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}

function FeedbackBlock({
  title,
  content,
  emphasis = false,
}: {
  title: string;
  content: string | null;
  emphasis?: boolean;
}) {
  return (
    <div>
      <h3 className={`text-xs font-semibold ${emphasis ? 'text-primary-600 dark:text-primary-400' : 'text-slate-500'}`}>
        {title}
      </h3>
      <p className="mt-1 whitespace-pre-wrap break-words leading-6 text-slate-700 dark:text-slate-300">
        {content || '暂无内容'}
      </p>
    </div>
  );
}

function SummaryReport({ summary }: { summary: TrainingSummary }) {
  return (
    <div className="space-y-6">
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm dark:border-slate-700 dark:bg-slate-800">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 text-emerald-600 dark:text-emerald-400">
              <CheckCircle2 className="h-5 w-5" />
              <span className="text-sm font-semibold">训练已完成</span>
            </div>
            <h2 className="mt-3 text-xl font-semibold text-slate-900 dark:text-white">本次训练总结</h2>
            <p className="mt-1 text-xs text-slate-400">{formatDateTime(summary.generatedAt)}</p>
          </div>
          <div className="text-right">
            <p className="text-4xl font-bold text-slate-900 dark:text-white">{summary.overallScore}</p>
            <p className="mt-1 text-xs text-slate-500">聚合得分 / 100</p>
          </div>
        </div>
        <p className="mt-5 whitespace-pre-wrap text-sm leading-6 text-slate-600 dark:text-slate-300">
          {summary.narrative}
        </p>
        <div className="mt-5 flex flex-wrap gap-x-6 gap-y-2 border-t border-slate-100 pt-4 text-sm text-slate-500 dark:border-slate-700">
          <span>完成 {summary.completedQuestionCount} 题</span>
          <span>覆盖 {summary.coveredTopicCount} 个主题</span>
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold text-slate-900 dark:text-white">主题表现</h2>
        <div className="space-y-3">
          {summary.topics.map(topic => (
            <div
              key={topic.topicKey}
              className="rounded-lg border border-slate-200 bg-white px-5 py-4 dark:border-slate-700 dark:bg-slate-800"
            >
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">{topic.displayName}</h3>
                  <p className="mt-1 text-xs text-slate-400">
                    历史 {Math.round(topic.originalAverageScore)} 分 · 本次回答 {topic.answeredQuestionCount} 题
                  </p>
                </div>
                <span className="text-lg font-bold text-primary-600 dark:text-primary-400">
                  {topic.trainingAverageScore}
                </span>
              </div>
              <div className="mt-3 h-1.5 overflow-hidden rounded bg-slate-200 dark:bg-slate-700">
                <div
                  className="h-full bg-primary-600"
                  style={{ width: `${Math.min(100, Math.max(0, topic.trainingAverageScore))}%` }}
                />
              </div>
            </div>
          ))}
        </div>
      </section>

      <div className="grid gap-4 md:grid-cols-3">
        <SummaryList title="表现亮点" icon={TrendingUp} items={summary.strengths} />
        <SummaryList title="待加强" icon={Target} items={summary.improvements} />
        <SummaryList title="下一步" icon={ChevronRight} items={summary.nextSteps} />
      </div>
    </div>
  );
}

function SummaryList({
  title,
  icon: Icon,
  items,
}: {
  title: string;
  icon: LucideIcon;
  items: string[];
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
      <div className="flex items-center gap-2">
        <Icon className="h-4 w-4 text-primary-600 dark:text-primary-400" />
        <h2 className="text-sm font-semibold text-slate-900 dark:text-white">{title}</h2>
      </div>
      <ul className="mt-3 space-y-2">
        {items.map((item, index) => (
          <li key={`${index}-${item}`} className="text-sm leading-5 text-slate-600 dark:text-slate-300">
            {item}
          </li>
        ))}
        {items.length === 0 && <li className="text-sm text-slate-400">暂无内容</li>}
      </ul>
    </section>
  );
}

function SessionStatus({ status }: { status: string }) {
  const labels: Record<string, string> = {
    READY: '待开始',
    IN_PROGRESS: '训练中',
    SUMMARIZING: '总结中',
    COMPLETED: '已完成',
    FAILED: '已失败',
  };
  return (
    <span className="rounded border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300">
      {labels[status] || status}
    </span>
  );
}

function ErrorBanner({ message, className = '' }: { message: string; className?: string }) {
  return (
    <div className={`flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300 ${className}`}>
      <AlertCircle className="mt-0.5 h-4 w-4 flex-none" />
      <span className="break-words">{message}</span>
    </div>
  );
}

function CenteredState({
  icon: Icon,
  title,
  detail,
  spinning = false,
  actionLabel,
  onAction,
}: {
  icon: LucideIcon;
  title: string;
  detail?: string;
  spinning?: boolean;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <section className="flex min-h-80 items-center justify-center rounded-lg border border-slate-200 bg-white p-8 text-center dark:border-slate-700 dark:bg-slate-800">
      <div>
        <Icon className={`mx-auto h-10 w-10 text-primary-600 dark:text-primary-400 ${spinning ? 'animate-spin' : ''}`} />
        <h2 className="mt-4 text-lg font-semibold text-slate-900 dark:text-white">{title}</h2>
        {detail && <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">{detail}</p>}
        {actionLabel && onAction && (
          <button
            type="button"
            onClick={onAction}
            className="mt-5 inline-flex items-center gap-2 rounded-lg bg-primary-600 px-4 py-2 text-sm font-semibold text-white hover:bg-primary-700"
          >
            <RefreshCw className="h-4 w-4" />
            {actionLabel}
          </button>
        )}
      </div>
    </section>
  );
}

export default function TrainingPage() {
  const { trainingId } = useParams<{ trainingId: string }>();
  return trainingId ? <TrainingWorkspace trainingId={trainingId} /> : <TrainingSetup />;
}
