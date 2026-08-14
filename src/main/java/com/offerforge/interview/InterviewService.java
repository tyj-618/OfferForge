package com.offerforge.interview;

import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AiStreamChunkConsumer;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.ai.ChatMessage;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.knowledge.Difficulty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 面试流程编排：start → ask(评分 + 状态转移 + 流式出题) → end。
 * 出题与评分由服务端控制（题库选题、引用服务端评分），LLM 仅负责话术生成。
 * <p>会话锁仅 JVM 内互斥，多实例部署需网关按 sessionId 粘性路由。</p>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    /** 空闲会话锁惰性清理：阈值与触发频率 */
    private static final long LOCK_IDLE_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final int LOCK_CLEANUP_INTERVAL = 512;
    private static final String OPENING_TEXT = "你好！我是 OfferForge 的 AI 面试官。本次模拟面试分为四个环节："
            + "基础考察、项目经历、深度追问与收尾总结。请先做一个简短的自我介绍（可包含项目经历与熟悉的技术栈），完成后我们正式开始。";

    private final InterviewSessionStore sessionStore;
    private final InterviewMessageStore messageStore;
    private final InterviewQuestionBank questionBank;
    private final StateTransitionStrategy strategy;
    private final InterviewPromptBuilder promptBuilder;
    private final AiModelClient aiModelClient;
    private final AnswerEvaluator answerEvaluator;
    private final FollowUpStrategy followUpStrategy;
    private final InterviewProperties properties;
    /** 会话级互斥锁条目（锁对象 + 最近使用时间），中途放弃的会话靠惰性清理避免无界增长 */
    private final Map<String, LockEntry> sessionLocks = new ConcurrentHashMap<>();
    private final AtomicLong lockAcquisitions = new AtomicLong();

    private record LockEntry(Object lock, long lastUsedMillis) {
    }

    public InterviewService(InterviewSessionStore sessionStore,
                            InterviewMessageStore messageStore,
                            InterviewQuestionBank questionBank,
                            StateTransitionStrategy strategy,
                            InterviewPromptBuilder promptBuilder,
                            AiModelClient aiModelClient,
                            AnswerEvaluator answerEvaluator,
                            FollowUpStrategy followUpStrategy,
                            InterviewProperties properties) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.questionBank = questionBank;
        this.strategy = strategy;
        this.promptBuilder = promptBuilder;
        this.aiModelClient = aiModelClient;
        this.answerEvaluator = answerEvaluator;
        this.followUpStrategy = followUpStrategy;
        this.properties = properties;
    }

    public InterviewStartResponse start(Long userId) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        InterviewContext context = new InterviewContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setState(InterviewState.OPENING);
        context.setCreatedAtEpochMillis(System.currentTimeMillis());
        sessionStore.save(context);
        messageStore.append(sessionId, List.of(ChatMessage.assistant(OPENING_TEXT)));
        log.info("interview started sessionId={} userId={}", sessionId, userId);
        return new InterviewStartResponse(sessionId, OPENING_TEXT, InterviewStatusResponse.from(context, properties));
    }

    public InterviewTurnResult answer(Long userId, String sessionId, String userMessage,
                                      AiStreamChunkConsumer chunkConsumer) throws IOException {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                if (context.getState().terminal()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，请勿继续作答");
                }
                messageStore.append(sessionId, List.of(ChatMessage.user(userMessage)));

                InterviewTurnResult result;
                if (context.getState() == InterviewState.OPENING) {
                    // 开场后的首次作答（自我介绍）不评分，直接进入基础考察
                    advance(context, sessionId, chunkConsumer);
                    result = new InterviewTurnResult(null, null, StateTransitionStrategy.Action.ADVANCE, statusView(context));
                } else if (context.getState() == InterviewState.CLOSING) {
                    finish(context, sessionId);
                    result = new InterviewTurnResult(null, null, StateTransitionStrategy.Action.FINISH, statusView(context));
                } else {
                    result = evaluateAndTransition(context, sessionId, userMessage, chunkConsumer);
                }
                sessionStore.save(context);
                return result;
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    public InterviewEndResponse end(Long userId, String sessionId) {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                InterviewEndResponse response = new InterviewEndResponse(
                        sessionId,
                        context.totalQuestionsAsked(),
                        context.averageScore(),
                        List.copyOf(context.getQuestionHistory()));
                if (!context.getState().terminal()) {
                    finish(context, sessionId);
                    sessionStore.save(context);
                }
                log.info("interview ended sessionId={} userId={} asked={} averageScore={}",
                        sessionId, userId, response.askedCount(), response.averageScore());
                return response;
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    public InterviewStatusResponse status(Long userId, String sessionId) {
        synchronized (lockFor(sessionId)) {
            return statusView(requireOwnedSession(userId, sessionId));
        }
    }

    private InterviewTurnResult evaluateAndTransition(InterviewContext context, String sessionId,
                                                      String userMessage, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        InterviewState phase = context.getState();
        AnswerEvaluation evaluation = answerEvaluator.evaluate(
                context.getCurrentQuestion(), context.getCurrentCandidateAnswer(), userMessage);
        double overall = evaluation.overall();
        context.recordAnswer(context.getCurrentQuestion(), userMessage, overall);
        updateScoreStreaks(context, overall);

        // 难度调整：连续低分立即降档；连续高分在确认留阶段换题时才升档（见下方 NEW_QUESTION 分支）
        boolean canRaise = context.getConsecutiveHighScores() >= 2
                && context.getCurrentDifficulty() != Difficulty.HARD;
        if (context.getConsecutiveLowScores() >= 2 && context.getCurrentDifficulty() != Difficulty.EASY) {
            context.setCurrentDifficulty(context.getCurrentDifficulty().lower());
            context.setConsecutiveLowScores(0);
            log.info("interview sessionId={} difficulty lowered to {}", sessionId, context.getCurrentDifficulty());
        }

        boolean poolExhausted = questionBank.nextQuestion(phase, askedSet(context), context.getCurrentDifficulty()).isEmpty();
        StateTransitionStrategy.DecisionInput input = new StateTransitionStrategy.DecisionInput(
                phase, overall, context.getCurrentFollowUpCount(), context.questionsInPhase(phase), poolExhausted, canRaise);
        StateTransitionStrategy.Action action = strategy.decide(input);
        log.info("interview sessionId={} phase={} score={} followUps={} difficulty={} action={}",
                sessionId, phase, overall, context.getCurrentFollowUpCount(), context.getCurrentDifficulty(), action);

        switch (action) {
            case FOLLOW_UP -> {
                String followUpQuestion = followUpStrategy.generateFollowUpQuestion(
                        context.getCurrentQuestion(), evaluation.missedPoints(), evaluation.wrongPoints());
                context.setCurrentQuestion(followUpQuestion);
                context.setCurrentQuestionFollowUp(true);
                context.setCurrentFollowUpCount(context.getCurrentFollowUpCount() + 1);
                List<ChatMessage> messages = promptBuilder.buildFollowUpMessages(
                        messageStore.list(sessionId), followUpQuestion);
                streamAndRecord(sessionId, messages, chunkConsumer);
            }
            case NEW_QUESTION -> {
                if (canRaise) {
                    // 消耗连击：升档后重新累计，避免连续逐题升档
                    context.setCurrentDifficulty(context.getCurrentDifficulty().raise());
                    context.setConsecutiveHighScores(0);
                    log.info("interview sessionId={} difficulty raised to {}", sessionId, context.getCurrentDifficulty());
                }
                askNextQuestion(context, sessionId, phase, chunkConsumer);
            }
            case ADVANCE -> advance(context, sessionId, chunkConsumer);
            case FINISH -> finish(context, sessionId);
        }
        return new InterviewTurnResult(overall, evaluation.feedback(), action, statusView(context));
    }

    /**
     * 更新连续高/低分连击：高分（>=7）累加 high 并重置 low，低分（<4）反之，中间分两者都重置。
     */
    private void updateScoreStreaks(InterviewContext context, double overall) {
        if (answerEvaluator.isStrong(overall)) {
            context.setConsecutiveHighScores(context.getConsecutiveHighScores() + 1);
            context.setConsecutiveLowScores(0);
        } else if (answerEvaluator.isPoor(overall)) {
            context.setConsecutiveLowScores(context.getConsecutiveLowScores() + 1);
            context.setConsecutiveHighScores(0);
        } else {
            context.setConsecutiveHighScores(0);
            context.setConsecutiveLowScores(0);
        }
    }

    /**
     * 推进到下一阶段；进入出题阶段时自动出下一题，题库耗尽则继续推进。
     */
    private void advance(InterviewContext context, String sessionId, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        InterviewState next = context.getState().next();
        if (next == InterviewState.FINISHED) {
            finish(context, sessionId);
            return;
        }
        context.setState(next);
        if (next == InterviewState.CLOSING) {
            String closing = closingText(context);
            messageStore.append(sessionId, List.of(ChatMessage.assistant(closing)));
            chunkConsumer.accept(closing);
            return;
        }
        askNextQuestion(context, sessionId, next, chunkConsumer);
    }

    private void askNextQuestion(InterviewContext context, String sessionId, InterviewState phase,
                                 AiStreamChunkConsumer chunkConsumer) throws IOException {
        var next = questionBank.nextQuestion(phase, askedSet(context), context.getCurrentDifficulty());
        if (next.isEmpty()) {
            // 该阶段题库耗尽，直接推进
            advance(context, sessionId, chunkConsumer);
            return;
        }
        InterviewQuestionBank.InterviewQuestion question = next.get();
        context.setCurrentQuestion(question.question());
        context.setCurrentCandidateAnswer(question.candidateAnswer());
        context.setCurrentQuestionPhase(phase);
        context.setCurrentKnowledgePoint(question.knowledgePoint());
        context.setCurrentQuestionFollowUp(false);
        context.setCurrentFollowUpCount(0);
        context.recordQuestionAsked(phase);
        List<ChatMessage> messages = promptBuilder.buildInterviewerMessages(
                messageStore.list(sessionId), phase, question.question());
        streamAndRecord(sessionId, messages, chunkConsumer);
    }

    /**
     * 流式生成面试官话术并落库 assistant 消息（同步捕获全文）。
     */
    private void streamAndRecord(String sessionId, List<ChatMessage> messages,
                                 AiStreamChunkConsumer chunkConsumer) throws IOException {
        StringBuilder fullText = new StringBuilder();
        aiModelClient.generateStream(messages, chunk -> {
            fullText.append(chunk);
            chunkConsumer.accept(chunk);
        });
        messageStore.append(sessionId, List.of(ChatMessage.assistant(fullText.toString())));
    }

    private void finish(InterviewContext context, String sessionId) {
        context.setState(InterviewState.FINISHED);
        context.setCurrentQuestion(null);
        messageStore.clear(sessionId);
        log.info("interview finished sessionId={} asked={}", sessionId, context.totalQuestionsAsked());
    }

    private String closingText(InterviewContext context) {
        return "本次面试的所有考察环节已结束。共作答 %d 题，平均得分 %.1f。感谢参与，回复任意内容即可结束本次会话。"
                .formatted(context.totalQuestionsAsked(), context.averageScore());
    }

    private InterviewStatusResponse statusView(InterviewContext context) {
        return InterviewStatusResponse.from(context, properties);
    }

    private Set<String> askedSet(InterviewContext context) {
        Set<String> asked = new HashSet<>();
        context.getQuestionHistory().forEach(record -> asked.add(record.getQuestion()));
        if (context.getCurrentQuestion() != null) {
            asked.add(context.getCurrentQuestion());
        }
        return asked;
    }

    private InterviewContext requireOwnedSession(Long userId, String sessionId) {
        InterviewContext context = sessionStore.find(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在"));
        if (context.getUserId() != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限操作该面试会话");
        }
        return context;
    }

    private Object lockFor(String sessionId) {
        if (lockAcquisitions.incrementAndGet() % LOCK_CLEANUP_INTERVAL == 0) {
            evictIdleLocks();
        }
        // 单条目原子合并：刷新使用时间且保留既有锁对象，避免双 Map 交错导致拿到不同锁
        LockEntry entry = sessionLocks.compute(sessionId, (ignored, existing) -> {
            Object lock = existing == null ? new Object() : existing.lock();
            return new LockEntry(lock, System.currentTimeMillis());
        });
        return entry.lock();
    }

    /**
     * 会话到达终态后清理锁条目。必须在释放锁之后执行，且用条件式 remove(key, value)：
     * 若在临界区内 remove，并发线程可能经 compute 拿到新锁对象导致互斥失效。
     */
    private void releaseLockIfTerminal(String sessionId, Object lock) {
        boolean terminal = sessionStore.find(sessionId)
                .map(context -> context.getState().terminal())
                .orElse(true);
        if (terminal) {
            LockEntry entry = sessionLocks.get(sessionId);
            if (entry != null && entry.lock() == lock) {
                // 条件式移除：条目被并发刷新（新锁对象）时保留，交给后续惰性清理
                sessionLocks.remove(sessionId, entry);
            }
        }
    }

    /** 清理长时间未使用的锁条目（客户端中途放弃的会话不会走终态路径） */
    private void evictIdleLocks() {
        long now = System.currentTimeMillis();
        sessionLocks.forEach((sessionId, entry) -> {
            if (now - entry.lastUsedMillis() > LOCK_IDLE_TIMEOUT_MILLIS) {
                // 条件式移除：条目已被并发刷新则保留
                if (sessionLocks.remove(sessionId, entry)) {
                    log.debug("evicted idle session lock sessionId={}", sessionId);
                }
            }
        });
    }
}
