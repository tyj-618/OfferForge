package com.offerforge.interview;

import com.offerforge.ai.AiGeneratedQuestion;
import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AiStreamChunkConsumer;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.ai.ChatMessage;
import com.offerforge.ai.LlmCallContext;
import com.offerforge.ai.LlmCredentialResolver;
import com.offerforge.apikey.ApiKeyService;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.exception.QuotaExceededException;
import com.offerforge.knowledge.Difficulty;
import com.offerforge.quota.QuotaService;
import com.offerforge.resume.ResumeService;
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
    /** 日志中用户回答预览的最大长度（脱敏：不打印全文） */
    private static final int ANSWER_LOG_PREVIEW_LENGTH = 100;
    /** 面试岗位方向缺省值 */
    public static final String DEFAULT_POSITION = "Java 后端工程师";

    private final InterviewSessionStore sessionStore;
    private final InterviewMessageStore messageStore;
    private final InterviewQuestionBank questionBank;
    private final StateTransitionStrategy strategy;
    private final InterviewPromptBuilder promptBuilder;
    private final AiModelClient aiModelClient;
    private final EvaluationService evaluationService;
    private final FollowUpStrategy followUpStrategy;
    private final ResumeService resumeService;
    private final ProjectQuestionGenerator projectQuestionGenerator;
    private final InterviewProperties properties;
    private final ApiKeyService apiKeyService;
    private final QuotaService quotaService;
    private final LlmCredentialResolver credentialResolver;
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
                            EvaluationService evaluationService,
                            FollowUpStrategy followUpStrategy,
                            ResumeService resumeService,
                            ProjectQuestionGenerator projectQuestionGenerator,
                            InterviewProperties properties,
                            ApiKeyService apiKeyService,
                            QuotaService quotaService,
                            LlmCredentialResolver credentialResolver) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.questionBank = questionBank;
        this.strategy = strategy;
        this.promptBuilder = promptBuilder;
        this.aiModelClient = aiModelClient;
        this.evaluationService = evaluationService;
        this.followUpStrategy = followUpStrategy;
        this.resumeService = resumeService;
        this.projectQuestionGenerator = projectQuestionGenerator;
        this.properties = properties;
        this.apiKeyService = apiKeyService;
        this.quotaService = quotaService;
        this.credentialResolver = credentialResolver;
    }

    public InterviewStartResponse start(Long userId, String position) {
        return start(userId, position, null, null);
    }

    public InterviewStartResponse start(Long userId, String position, Long resumeId) {
        return start(userId, position, resumeId, null);
    }

    /**
     * 开始面试：resumeId 可空；非空时校验归属，PROJECT/DEEP 阶段将基于该简历出题。
     * mode：training / practice，空或非法值按 practice 处理。
     * 额度策略：有自带 Key 直接开始；无 Key 时扣减一次免费额度，耗尽拒绝。
     */
    public InterviewStartResponse start(Long userId, String position, Long resumeId, String mode) {
        if (sessionStore.hasActiveSession(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已有一场面试正在进行，请先结束后再开始新面试");
        }
        if (resumeId != null) {
            // 先校验简历归属（不存在/非本人均 NOT_FOUND），避免非法简历白白消耗免费额度
            resumeService.getOwned(userId, resumeId);
        }
        String keySource;
        if (apiKeyService.hasKey(userId)) {
            keySource = "user";
        } else if (!quotaService.isEnabled() || quotaService.consumeQuota(userId)) {
            keySource = "system";
        } else {
            throw new QuotaExceededException(quotaService.checkQuota(userId));
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        InterviewContext context = new InterviewContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setPosition(position == null || position.isBlank() ? DEFAULT_POSITION : position.trim());
        context.setResumeId(resumeId);
        context.setMode(InterviewContext.MODE_TRAINING.equals(mode) ? InterviewContext.MODE_TRAINING : InterviewContext.MODE_PRACTICE);
        context.setState(InterviewState.OPENING);
        context.setCreatedAtEpochMillis(System.currentTimeMillis());
        sessionStore.save(context);
        messageStore.append(sessionId, List.of(ChatMessage.assistant(OPENING_TEXT)));
        log.info("interview started sessionId={} userId={} position={} resumeId={} mode={} keySource={} remainingQuota={}",
                sessionId, userId, context.getPosition(), resumeId, context.getMode(), keySource, quotaService.checkQuota(userId));
        return new InterviewStartResponse(sessionId, OPENING_TEXT, InterviewStatusResponse.from(context, properties));
    }

    public InterviewTurnResult answer(Long userId, String sessionId, String userMessage,
                                      AiStreamChunkConsumer chunkConsumer) throws IOException {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                // 绑定本场 LLM 凭据（自带 Key 或系统配置）与 token 用量监听；本轮结束清理防线程复用泄漏
                LlmCallContext.bind(credentialResolver.resolveFor(userId));
                LlmCallContext.setUsageListener(context::addTokenUsage);
                try {
                    if (context.getState().terminal()) {
                        throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，请勿继续作答");
                    }
                    log.info("interview answer received sessionId={} phase={} length={} preview={}",
                            sessionId, context.getState(), userMessage.length(), preview(userMessage));
                    messageStore.append(sessionId, List.of(ChatMessage.user(userMessage)));

                    InterviewTurnResult result;
                    if (context.getState() == InterviewState.DEEP_TRAINING) {
                        // 深度训练子流程：递进题评估 + 达标/上限判定，不走主状态机
                        result = answerDeepTraining(context, sessionId, userMessage, chunkConsumer);
                    } else if (context.getState() == InterviewState.OPENING) {
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
                } finally {
                    LlmCallContext.clear();
                }
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    /**
     * 结束面试并返回工作记忆上下文：置终态、清理对话消息，供报告生成与归档使用。
     * 已结束的会话幂等返回当前上下文。
     */
    public InterviewContext finishInterview(Long userId, String sessionId) {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                if (!context.getState().terminal()) {
                    finish(context, sessionId);
                    sessionStore.save(context);
                }
                log.info("interview finished sessionId={} userId={} asked={}",
                        sessionId, userId, context.totalQuestionsAsked());
                return context;
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

    /**
     * 跳过当前题：记 0 分（与答错同等对待，防止靠跳过规避难题），然后推进到下一题或下一阶段。
     * 仅出题阶段（BASICS/PROJECT/DEEP）可用；开场/收尾阶段无需跳过。
     */
    public InterviewTurnResult skip(Long userId, String sessionId, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                LlmCallContext.bind(credentialResolver.resolveFor(userId));
                LlmCallContext.setUsageListener(context::addTokenUsage);
                try {
                    if (context.getState().terminal()) {
                        throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，无需跳过");
                    }
                    InterviewState phase = context.getState();
                    if (phase == InterviewState.OPENING || phase == InterviewState.CLOSING) {
                        throw new BusinessException(ErrorCode.CONFLICT, "当前环节无需跳题，直接回复即可");
                    }
                    if (phase == InterviewState.DEEP_TRAINING) {
                        throw new BusinessException(ErrorCode.CONFLICT, "深度训练中请作答当前题，可点击“退出深度训练”返回主面试");
                    }
                    log.info("interview sessionId={} phase={} question skipped", sessionId, phase);
                    messageStore.append(sessionId, List.of(ChatMessage.user("（跳过此题）")));

                    // 记 0 分并纳入平均分，连续低分连击照常累计（可能触发降难度）
                    AnswerEvaluation skipped = new AnswerEvaluation(
                            0, 0, 0, 0, 0, List.of(), List.of(), List.of(), "候选人跳过了该题", null, null, null);
                    context.recordAnswer(context.getCurrentQuestion(), "", skipped);
                    updateScoreStreaks(context, 0);
                    if (context.getConsecutiveLowScores() >= 2 && context.getCurrentDifficulty() != Difficulty.EASY) {
                        context.setCurrentDifficulty(context.getCurrentDifficulty().lower());
                        context.setConsecutiveLowScores(0);
                        log.info("interview sessionId={} difficulty lowered to {}", sessionId, context.getCurrentDifficulty());
                    }

                    StateTransitionStrategy.Action action;
                    if (context.questionsInPhase(phase) >= properties.maxQuestionsFor(phase)) {
                        // 本阶段题量已满，跳过后直接进入下一阶段
                        action = StateTransitionStrategy.Action.ADVANCE;
                        advance(context, sessionId, chunkConsumer);
                    } else {
                        action = StateTransitionStrategy.Action.NEW_QUESTION;
                        askNextQuestion(context, sessionId, phase, chunkConsumer);
                    }
                    sessionStore.save(context);
                    // 实战模式过程免评分：不向前端暴露 0 分与计分明细，点评改中性
                    if (context.isTrainingMode()) {
                        return new InterviewTurnResult(0.0, "已跳过该题，计 0 分", action, statusView(context));
                    }
                    return new InterviewTurnResult(null, "已跳过该题", action, statusView(context));
                } finally {
                    LlmCallContext.clear();
                }
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    private InterviewTurnResult evaluateAndTransition(InterviewContext context, String sessionId,
                                                      String userMessage, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        InterviewState phase = context.getState();
        // 训练模式要求详细反馈（goodPoints/badPoints/improvedAnswer），实战模式常规评估即可
        AnswerEvaluation evaluation = evaluationService.evaluate(
                context.getCurrentQuestion(), context.getCurrentKnowledgePoint(),
                context.getCurrentCandidateAnswer(), userMessage, context.isTrainingMode());
        double overall = evaluation.overall();
        context.recordAnswer(context.getCurrentQuestion(), userMessage, evaluation);
        updateScoreStreaks(context, overall);

        // 难度调整：连续低分立即降档；连续高分在确认留阶段换题时才升档（见下方 NEW_QUESTION 分支）
        boolean canRaise = context.getConsecutiveHighScores() >= 2
                && context.getCurrentDifficulty() != Difficulty.HARD;
        if (context.getConsecutiveLowScores() >= 2 && context.getCurrentDifficulty() != Difficulty.EASY) {
            context.setCurrentDifficulty(context.getCurrentDifficulty().lower());
            context.setConsecutiveLowScores(0);
            log.info("interview sessionId={} difficulty lowered to {}", sessionId, context.getCurrentDifficulty());
        }

        boolean poolExhausted = questionBank.nextQuestion(phase, askedSet(context), context.getCurrentDifficulty()).isEmpty()
                && context.getPreparedQuestions().isEmpty();
        StateTransitionStrategy.DecisionInput input = new StateTransitionStrategy.DecisionInput(
                phase, overall, context.getCurrentFollowUpCount(), context.questionsInPhase(phase), poolExhausted, canRaise);
        StateTransitionStrategy.Action action = strategy.decide(input);
        log.info("interview sessionId={} phase={} score={} followUps={} difficulty={} action={}",
                sessionId, phase, overall, context.getCurrentFollowUpCount(), context.getCurrentDifficulty(), action);

        switch (action) {
            case FOLLOW_UP -> {
                String followUpQuestion = followUpStrategy.generateFollowUpQuestion(
                        context.getCurrentQuestion(), evaluation.missedPoints(), evaluation.wrongPoints());
                if (context.isTrainingMode()) {
                    // 训练模式：追问暂不发出，暂存作为“深度训练”触发信号（前端据 followUpChoiceRequired 展示按钮），
                    // 用户选择“深度训练”后丢弃暂存追问进入子流程
                    context.setPendingFollowUpQuestion(followUpQuestion);
                    log.info("interview sessionId={} follow-up pending user choice", sessionId);
                } else {
                    askFollowUpQuestion(context, sessionId, followUpQuestion, chunkConsumer);
                }
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
        // 实战模式过程免评分：评分照常入库（状态机/报告依赖），但不向前端返回评分与点评
        if (context.isTrainingMode()) {
            return new InterviewTurnResult(overall, evaluation.feedback(), action, statusView(context), evaluation);
        }
        return new InterviewTurnResult(null, null, action, statusView(context));
    }

    /**
     * 训练模式“深度训练”：丢弃暂存追问（仅保留为触发信号），进入 DEEP_TRAINING 子流程并发出第 1 道递进题。
     * 仅训练模式且有待选追问时可用；递进题不计主流程已问题数/平均分。
     */
    public InterviewTurnResult enterDeepTraining(Long userId, String sessionId, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                LlmCallContext.bind(credentialResolver.resolveFor(userId));
                LlmCallContext.setUsageListener(context::addTokenUsage);
                try {
                    if (context.getState().terminal()) {
                        throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，请勿继续作答");
                    }
                    if (!context.isTrainingMode() || context.getPendingFollowUpQuestion() == null) {
                        throw new BusinessException(ErrorCode.CONFLICT, "当前无可深度训练的知识点");
                    }
                    context.setPendingFollowUpQuestion(null);
                    context.setDeepTrainingReturnState(context.getState());
                    context.setDeepTrainingAsked(0);
                    context.setDeepTrainingConsecutivePass(0);
                    context.setState(InterviewState.DEEP_TRAINING);
                    log.info("interview sessionId={} entered deep training, return state={}",
                            sessionId, context.getDeepTrainingReturnState());
                    askDeepTrainingQuestion(context, sessionId, chunkConsumer);
                    sessionStore.save(context);
                    return new InterviewTurnResult(null, null, null, statusView(context));
                } finally {
                    LlmCallContext.clear();
                }
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    /**
     * 主动退出深度训练：恢复主面试阶段并出下一题（题量已满则推进）。
     */
    public InterviewTurnResult exitDeepTraining(Long userId, String sessionId, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                LlmCallContext.bind(credentialResolver.resolveFor(userId));
                LlmCallContext.setUsageListener(context::addTokenUsage);
                try {
                    if (context.getState().terminal()) {
                        throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，请勿继续作答");
                    }
                    if (context.getState() != InterviewState.DEEP_TRAINING) {
                        throw new BusinessException(ErrorCode.CONFLICT, "当前不在深度训练中");
                    }
                    log.info("interview sessionId={} deep training exited after {} questions",
                            sessionId, context.getDeepTrainingAsked());
                    emitPlain(sessionId, "好的，我们退出深度训练，回到主面试。", chunkConsumer);
                    StateTransitionStrategy.Action action = resumeFromDeepTraining(context, sessionId, chunkConsumer);
                    sessionStore.save(context);
                    return new InterviewTurnResult(null, null, action, statusView(context));
                } finally {
                    LlmCallContext.clear();
                }
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    /**
     * 深度训练中的作答：详细评估 + 连续达标累计；
     * 连续 2 题 ≥ 6 分达标或达上限 5 题后均自动返回主面试（防无限循环）。
     */
    private InterviewTurnResult answerDeepTraining(InterviewContext context, String sessionId,
                                                   String userMessage, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        AnswerEvaluation evaluation = evaluationService.evaluate(
                context.getCurrentQuestion(), context.getCurrentKnowledgePoint(), null, userMessage, true);
        double overall = evaluation.overall();
        // 深度训练题标记 followUp + deepTraining：不计入主流程已问题数/平均分/追问统计
        context.recordAnswer(context.getCurrentQuestion(), userMessage, evaluation, true);
        boolean pass = overall >= properties.getDeepTrainingPassScore();
        context.setDeepTrainingConsecutivePass(pass ? context.getDeepTrainingConsecutivePass() + 1 : 0);
        boolean achieved = context.getDeepTrainingConsecutivePass() >= properties.getDeepTrainingPassStreak();
        boolean usedUp = context.getDeepTrainingAsked() >= properties.getMaxDeepTrainingQuestions();
        log.info("interview sessionId={} deep training answer score={} pass={} streak={} asked={}",
                sessionId, overall, pass, context.getDeepTrainingConsecutivePass(), context.getDeepTrainingAsked());

        StateTransitionStrategy.Action action = null;
        if (achieved) {
            emitPlain(sessionId, "很棒！你已连续 " + properties.getDeepTrainingPassStreak()
                    + " 题达标，这个知识点已经得到强化，我们回到主面试继续。", chunkConsumer);
            action = resumeFromDeepTraining(context, sessionId, chunkConsumer);
        } else if (usedUp) {
            emitPlain(sessionId, "深度训练已完成 " + properties.getMaxDeepTrainingQuestions()
                    + " 题，我们先回到主面试，建议面试结束后针对该知识点再巩固。", chunkConsumer);
            action = resumeFromDeepTraining(context, sessionId, chunkConsumer);
        } else {
            askDeepTrainingQuestion(context, sessionId, chunkConsumer);
        }
        return new InterviewTurnResult(overall, evaluation.feedback(), action, statusView(context), evaluation);
    }

    /**
     * 发出深度训练递进题：生成题面后经话术包装流式发出；题序与已问题清单传入防重复。
     */
    private void askDeepTrainingQuestion(InterviewContext context, String sessionId,
                                         AiStreamChunkConsumer chunkConsumer) throws IOException {
        int index = context.getDeepTrainingAsked() + 1;
        String question = generateDeepTrainingQuestion(context, index);
        context.setCurrentQuestion(question);
        context.setCurrentQuestionFollowUp(true);
        context.setCurrentQuestionPhase(InterviewState.DEEP_TRAINING);
        context.setDeepTrainingAsked(index);
        List<ChatMessage> messages = promptBuilder.buildDeepTrainingMessages(
                messageStore.list(sessionId), question, index);
        streamAndRecord(sessionId, messages, chunkConsumer);
    }

    /**
     * 生成深度训练递进题：锚点题取主流程最近一道非追问题；生成失败降级为通用递进话术。
     */
    private String generateDeepTrainingQuestion(InterviewContext context, int index) {
        String anchor = context.getQuestionHistory().stream()
                .filter(record -> !record.isFollowUp())
                .reduce((ignored, latest) -> latest)
                .map(QuestionRecord::getQuestion)
                .orElse(context.getCurrentQuestion());
        Set<String> asked = context.getQuestionHistory().stream()
                .filter(QuestionRecord::isDeepTraining)
                .map(QuestionRecord::getQuestion)
                .collect(java.util.stream.Collectors.toSet());
        try {
            String prompt = promptBuilder.buildDeepTrainingQuestionPrompt(
                    context.getCurrentKnowledgePoint(), anchor, index, asked);
            AiGeneratedQuestion generated = aiModelClient.generateDeepQuestion(prompt);
            if (generated != null && generated.question() != null && !generated.question().isBlank()) {
                return generated.question().trim();
            }
        } catch (RuntimeException exception) {
            log.warn("deep training question generation failed, fallback to generic prompt sessionId={}",
                    context.getSessionId(), exception);
        }
        String knowledgePoint = context.getCurrentKnowledgePoint() == null ? "该知识点" : context.getCurrentKnowledgePoint();
        return "关于「" + knowledgePoint + "」，请换一个角度再深入讲讲你的理解（深度训练第 " + index + " 题）。";
    }

    /**
     * 从深度训练返回主面试：恢复阶段与题目标记，出该阶段下一题；题量已满则推进下一阶段。
     */
    private StateTransitionStrategy.Action resumeFromDeepTraining(InterviewContext context, String sessionId,
                                                                  AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        InterviewState returnState = context.getDeepTrainingReturnState();
        if (returnState == null || !returnState.questioning()) {
            // 防御：旧会话缺字段时降级回基础考察，避免卡在子流程
            returnState = InterviewState.BASICS;
        }
        context.setState(returnState);
        context.setDeepTrainingReturnState(null);
        context.setDeepTrainingAsked(0);
        context.setDeepTrainingConsecutivePass(0);
        context.setCurrentQuestionFollowUp(false);
        context.setCurrentFollowUpCount(0);
        if (context.questionsInPhase(returnState) >= properties.maxQuestionsFor(returnState)) {
            advance(context, sessionId, chunkConsumer);
            return StateTransitionStrategy.Action.ADVANCE;
        }
        askNextQuestion(context, sessionId, returnState, chunkConsumer);
        return StateTransitionStrategy.Action.NEW_QUESTION;
    }

    /** 非 LLM 的固定话术：直接入消息库并推给前端 */
    private void emitPlain(String sessionId, String text, AiStreamChunkConsumer chunkConsumer) throws IOException {
        messageStore.append(sessionId, List.of(ChatMessage.assistant(text)));
        chunkConsumer.accept(text);
    }

    /**
     * 训练模式“下一板块”：放弃暂存追问（原低分已入账，不额外计分），推进到同阶段下一题或下一阶段。
     */
    public InterviewTurnResult chooseNextQuestion(Long userId, String sessionId, AiStreamChunkConsumer chunkConsumer)
            throws IOException {
        Object lock = lockFor(sessionId);
        try {
            synchronized (lock) {
                InterviewContext context = requireOwnedSession(userId, sessionId);
                LlmCallContext.bind(credentialResolver.resolveFor(userId));
                LlmCallContext.setUsageListener(context::addTokenUsage);
                try {
                    if (context.getState().terminal()) {
                        throw new BusinessException(ErrorCode.CONFLICT, "面试已结束，请勿继续作答");
                    }
                    if (!context.isTrainingMode() || context.getPendingFollowUpQuestion() == null) {
                        throw new BusinessException(ErrorCode.CONFLICT, "当前无需选择下一题");
                    }
                    context.setPendingFollowUpQuestion(null);
                    InterviewState phase = context.getState();
                    log.info("interview sessionId={} user skipped follow-up, next question in {}", sessionId, phase);
                    StateTransitionStrategy.Action action;
                    if (context.questionsInPhase(phase) >= properties.maxQuestionsFor(phase)) {
                        action = StateTransitionStrategy.Action.ADVANCE;
                        advance(context, sessionId, chunkConsumer);
                    } else {
                        action = StateTransitionStrategy.Action.NEW_QUESTION;
                        askNextQuestion(context, sessionId, phase, chunkConsumer);
                    }
                    sessionStore.save(context);
                    return new InterviewTurnResult(null, null, action, statusView(context));
                } finally {
                    LlmCallContext.clear();
                }
            }
        } finally {
            releaseLockIfTerminal(sessionId, lock);
        }
    }

    /**
     * 发出追问：置当前题为追问、累计追问次数，经话术包装后流式发出。
     */
    private void askFollowUpQuestion(InterviewContext context, String sessionId, String followUpQuestion,
                                     AiStreamChunkConsumer chunkConsumer) throws IOException {
        context.setCurrentQuestion(followUpQuestion);
        context.setCurrentQuestionFollowUp(true);
        context.setCurrentFollowUpCount(context.getCurrentFollowUpCount() + 1);
        List<ChatMessage> messages = promptBuilder.buildFollowUpMessages(
                messageStore.list(sessionId), followUpQuestion);
        streamAndRecord(sessionId, messages, chunkConsumer);
    }

    /**
     * 更新连续高/低分连击：高分（>=7）累加 high 并重置 low，低分（<4）反之，中间分两者都重置。
     */
    private void updateScoreStreaks(InterviewContext context, double overall) {
        if (evaluationService.isStrong(overall)) {
            context.setConsecutiveHighScores(context.getConsecutiveHighScores() + 1);
            context.setConsecutiveLowScores(0);
        } else if (evaluationService.isPoor(overall)) {
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
        InterviewQuestionBank.InterviewQuestion question = nextPreparedOrBank(context, phase);
        if (question == null) {
            // 该阶段备选队列与题库均耗尽，直接推进
            advance(context, sessionId, chunkConsumer);
            return;
        }
        context.setCurrentQuestion(question.question());
        context.setCurrentCandidateAnswer(question.candidateAnswer());
        context.setCurrentQuestionPhase(phase);
        context.setCurrentKnowledgePoint(question.knowledgePoint());
        context.setCurrentQuestionFollowUp(false);
        context.setCurrentFollowUpCount(0);
        context.recordQuestionAsked(phase);
        log.info("interview question asked sessionId={} phase={} difficulty={} followUp={} question={}",
                sessionId, phase, context.getCurrentDifficulty(), context.isCurrentQuestionFollowUp(),
                preview(question.question()));
        List<ChatMessage> messages = promptBuilder.buildInterviewerMessages(
                messageStore.list(sessionId), phase, question.question(), context.getMode());
        streamAndRecord(sessionId, messages, chunkConsumer);
    }

    /**
     * 取下一题：优先消费基于简历生成的备选队列，队列耗尽后回退通用题库。
     */
    private InterviewQuestionBank.InterviewQuestion nextPreparedOrBank(InterviewContext context, InterviewState phase) {
        ensurePreparedQuestions(context, phase);
        if (!context.getPreparedQuestions().isEmpty()) {
            return context.getPreparedQuestions().remove(0);
        }
        return questionBank.nextQuestion(phase, askedSet(context), context.getCurrentDifficulty()).orElse(null);
    }

    /**
     * 惰性生成备选队列（每阶段只触发一次）：
     * PROJECT 需关联简历；DEEP 基于 PROJECT 阶段作答记录（低分优先）。生成异常降级到通用题库。
     */
    private void ensurePreparedQuestions(InterviewContext context, InterviewState phase) {
        if (phase == InterviewState.PROJECT && !context.isProjectQuestionsGenerated()) {
            context.setProjectQuestionsGenerated(true);
            if (context.getResumeId() != null) {
                try {
                    context.getPreparedQuestions().addAll(projectQuestionGenerator.generateProjectQuestions(
                            context.getUserId(), context.getResumeId()));
                } catch (RuntimeException exception) {
                    log.warn("project question generation failed, fallback to generic bank sessionId={}",
                            context.getSessionId(), exception);
                }
            }
        } else if (phase == InterviewState.DEEP && !context.isDeepQuestionsGenerated()) {
            context.setDeepQuestionsGenerated(true);
            // 上一阶段的剩余备选题不再使用
            context.getPreparedQuestions().clear();
            try {
                context.getPreparedQuestions().addAll(projectQuestionGenerator.generateDeepQuestions(
                        context, properties.maxQuestionsFor(InterviewState.DEEP)));
            } catch (RuntimeException exception) {
                log.warn("deep question generation failed, fallback to knowledge bank sessionId={}",
                        context.getSessionId(), exception);
            }
        }
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
        log.info("interview finished sessionId={} asked={} inputTokens={} outputTokens={}",
                sessionId, context.totalQuestionsAsked(), context.getInputTokens(), context.getOutputTokens());
    }

    private String closingText(InterviewContext context) {
        if (context.isTrainingMode()) {
            return "本次面试的所有考察环节已结束。共作答 %d 题，平均得分 %.1f。感谢参与，回复任意内容即可结束本次会话。"
                    .formatted(context.totalQuestionsAsked(), context.averageScore());
        }
        // 实战模式过程免评分：收尾不透露平均分，完整反馈在结束后统一报告
        return "本次面试的所有考察环节已结束，感谢参与。回复任意内容即可结束本次会话，结束后将生成完整的反馈报告。";
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

    /** 日志脱敏：用户回答只保留前 100 字符预览，不打印全文 */
    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= ANSWER_LOG_PREVIEW_LENGTH ? value : value.substring(0, ANSWER_LOG_PREVIEW_LENGTH) + "…";
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
