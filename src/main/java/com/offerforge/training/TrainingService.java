package com.offerforge.training;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.ai.ChatMessage;
import com.offerforge.ai.LlmCallContext;
import com.offerforge.ai.LlmCredentialResolver;
import com.offerforge.apikey.ApiKeyService;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.exception.QuotaExceededException;
import com.offerforge.interview.EvaluationService;
import com.offerforge.interview.InterviewMessageStore;
import com.offerforge.interview.InterviewPromptBuilder;
import com.offerforge.interview.InterviewQuestionBank;
import com.offerforge.interview.InterviewSessionStore;
import com.offerforge.interview.InterviewState;
import com.offerforge.interview.InterviewStreamSink;
import com.offerforge.knowledge.Difficulty;
import com.offerforge.knowledge.KnowledgeMasteryService;
import com.offerforge.knowledge.KnowledgeRepository;
import com.offerforge.quota.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专项训练流程编排（任务 7）：start（校验分组可见性 + 额度）→ answer（详细评估 + 导师反馈
 * + 由浅入深升降档 + 递进出题）→ 达标题数/题库耗尽/主动结束后归档简要成绩。
 * <p>出题复用面试题库的可见性隔离查询；SSE 契约复用面试的 message/segment/progress/done/error 结构。</p>
 */
@Service
public class TrainingService {

    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);
    /** 日志中用户回答预览的最大长度（脱敏：不打印全文） */
    private static final int ANSWER_LOG_PREVIEW_LENGTH = 100;

    private final TrainingSessionStore sessionStore;
    private final InterviewSessionStore interviewSessionStore;
    private final InterviewMessageStore messageStore;
    private final InterviewQuestionBank questionBank;
    private final TrainingPromptBuilder promptBuilder;
    private final InterviewPromptBuilder mentorPromptBuilder;
    private final AiModelClient aiModelClient;
    private final EvaluationService evaluationService;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeMasteryService masteryService;
    private final TrainingRecordRepository recordRepository;
    private final TrainingProperties properties;
    private final ApiKeyService apiKeyService;
    private final QuotaService quotaService;
    private final LlmCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    /** 会话级互斥锁：训练会话量小，终态时移除条目即可 */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public TrainingService(TrainingSessionStore sessionStore,
                           InterviewSessionStore interviewSessionStore,
                           InterviewMessageStore messageStore,
                           InterviewQuestionBank questionBank,
                           TrainingPromptBuilder promptBuilder,
                           InterviewPromptBuilder mentorPromptBuilder,
                           AiModelClient aiModelClient,
                           EvaluationService evaluationService,
                           KnowledgeRepository knowledgeRepository,
                           KnowledgeMasteryService masteryService,
                           TrainingRecordRepository recordRepository,
                           TrainingProperties properties,
                           ApiKeyService apiKeyService,
                           QuotaService quotaService,
                           LlmCredentialResolver credentialResolver,
                           ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.interviewSessionStore = interviewSessionStore;
        this.messageStore = messageStore;
        this.questionBank = questionBank;
        this.promptBuilder = promptBuilder;
        this.mentorPromptBuilder = mentorPromptBuilder;
        this.aiModelClient = aiModelClient;
        this.evaluationService = evaluationService;
        this.knowledgeRepository = knowledgeRepository;
        this.masteryService = masteryService;
        this.recordRepository = recordRepository;
        this.properties = properties;
        this.apiKeyService = apiKeyService;
        this.quotaService = quotaService;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 开始专项训练：校验分组对该用户可见且有题；额度策略与模拟面试一致
     * （有自带 Key 直接开始；无 Key 扣减一次免费额度，耗尽拒绝）。
     * 开场即出第 1 题（EASY 起步），经教练话术包装后随响应返回。
     */
    public TrainingStartResponse start(Long userId, String category) {
        return start(userId, category, null, false);
    }

    /**
     * 完整参数版：style 为助手语气风格（strict/friendly，缺省 friendly）；
     * fromInterview=true 表示面试「深入该模块」跳转，豁免与面试会话的跨模块互斥。
     */
    public TrainingStartResponse start(Long userId, String category, String style, boolean fromInterview) {
        String normalized = category == null ? null : category.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要训练的资料分组");
        }
        if (sessionStore.hasActiveSession(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已有一场专项训练正在进行，请先结束后再开始新训练");
        }
        // 跨模块互斥：同一用户同一时刻只能进行一场面试或训练（多端一致）；面试深入跳转豁免
        if (!fromInterview && interviewSessionStore.hasActiveSession(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "你有一场模拟面试正在进行，请先完成或结束后再开始训练");
        }
        // 分组可见性：官方 + 本人私有条目均无该分组时拒绝，避免开局即无题
        if (knowledgeRepository.findVisibleByCategories(List.of(normalized), userId).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该分组下暂无可用题目，请先上传资料或更换分组");
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
        TrainingContext context = new TrainingContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setCategory(normalized);
        context.setStyle(style);
        context.setCreatedAtEpochMillis(System.currentTimeMillis());

        InterviewQuestionBank.InterviewQuestion first = nextQuestion(context).orElseThrow(
                () -> new BusinessException(ErrorCode.PARAM_ERROR, "该分组下暂无可用题目，请先上传资料或更换分组"));
        applyQuestion(context, first);
        sessionStore.save(context);
        LlmCallContext.bind(credentialResolver.resolveFor(userId));
        String openingMessage;
        try {
            openingMessage = streamAndRecord(sessionId,
                    promptBuilder.buildCoachMessages(messageStore.list(sessionId), normalized,
                            first.question(), 1, context.getCurrentDifficulty().label(), context.getStyle()));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "训练开场生成失败，请稍后重试");
        } finally {
            LlmCallContext.clear();
        }
        log.info("training started sessionId={} userId={} category={} keySource={} remainingQuota={}",
                sessionId, userId, normalized, keySource, quotaService.checkQuota(userId));
        return new TrainingStartResponse(sessionId, openingMessage, statusView(context));
    }

    /**
     * 作答：详细评估（训练模式同款）+ 导师反馈气泡 + 难度升降档 + 下一题或完成归档。
     * 回合全程标记 evaluating 并落库：客户端中途刷新后可凭 status 轮询续接未完成回合。
     */
    public TrainingTurnResult answer(Long userId, String sessionId, String userMessage,
                                     InterviewStreamSink sink) throws IOException {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            TrainingContext context = requireOwnedSession(userId, sessionId);
            if (context.isFinished()) {
                throw new BusinessException(ErrorCode.CONFLICT, "本场专项训练已完成");
            }
            LlmCallContext.bind(credentialResolver.resolveFor(userId));
            try {
                return answerInternal(context, sessionId, userMessage, false, sink);
            } catch (IOException | RuntimeException exception) {
                // 回合中途失败：复位评估标记，避免刷新恢复时永久卡在轮询
                context.setEvaluating(false);
                sessionStore.save(context);
                throw exception;
            } finally {
                LlmCallContext.clear();
                releaseLockIfTerminal(sessionId, lock);
            }
        }
    }

    /**
     * 标记当前题「已掌握」（绿勾）：题目直接 pass——不计入 askedCount、不入 history，仅登记防重复清单；
     * 对应资料问答加绿勾；随后教练话术出下一题，题库耗尽走既有完成分支。
     */
    public TrainingTurnResult mastered(Long userId, String sessionId, InterviewStreamSink sink) throws IOException {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            TrainingContext context = requireOwnedSession(userId, sessionId);
            if (context.isFinished()) {
                throw new BusinessException(ErrorCode.CONFLICT, "本场专项训练已完成");
            }
            String question = context.getCurrentQuestion();
            log.info("training sessionId={} question marked mastered", sessionId);
            masteryService.resolveItemId(question, context.getUserId())
                    .ifPresent(itemId -> masteryService.recordCheck(context.getUserId(), itemId));
            messageStore.append(sessionId, List.of(ChatMessage.user("（已掌握本题，继续下一题）")));
            context.recordPassedQuestion(question);
            // 同作答回合：标记评估中，正常/异常路径均复位
            context.setEvaluating(true);
            sessionStore.save(context);
            LlmCallContext.bind(credentialResolver.resolveFor(userId));
            try {
                boolean finished = advanceToNextOrFinish(context, sessionId, sink);
                context.setEvaluating(false);
                sessionStore.save(context);
                // score 为 null：前端不展示得分徽章
                return new TrainingTurnResult(null, null, finished, statusView(context), null);
            } catch (IOException | RuntimeException exception) {
                context.setEvaluating(false);
                sessionStore.save(context);
                throw exception;
            } finally {
                LlmCallContext.clear();
                releaseLockIfTerminal(sessionId, lock);
            }
        }
    }

    /**
     * 标记当前题「不知道」（红叉）：等价作答「不知道」走完整评估反馈流程（综合分强制 0），
     * 对应资料问答加红叉；后续推进与正常作答一致。
     */
    public TrainingTurnResult dontknow(Long userId, String sessionId, InterviewStreamSink sink) throws IOException {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            TrainingContext context = requireOwnedSession(userId, sessionId);
            if (context.isFinished()) {
                throw new BusinessException(ErrorCode.CONFLICT, "本场专项训练已完成");
            }
            log.info("training sessionId={} question marked dont-know", sessionId);
            masteryService.resolveItemId(context.getCurrentQuestion(), context.getUserId())
                    .ifPresent(itemId -> masteryService.recordCross(context.getUserId(), itemId));
            LlmCallContext.bind(credentialResolver.resolveFor(userId));
            try {
                // 复用作答主流程：红叉已预先记录，强制 0 分后联动跳过避免重复记录
                return answerInternal(context, sessionId, "不知道", true, sink);
            } catch (IOException | RuntimeException exception) {
                context.setEvaluating(false);
                sessionStore.save(context);
                throw exception;
            } finally {
                LlmCallContext.clear();
                releaseLockIfTerminal(sessionId, lock);
            }
        }
    }

    /**
     * 作答回合骨架：详细评估（forceZeroScore 时综合分强制 0）+ 导师反馈 + 难度升降档 + 下一题/完成归档。
     */
    private TrainingTurnResult answerInternal(TrainingContext context, String sessionId, String userMessage,
                                              boolean forceZeroScore, InterviewStreamSink sink) throws IOException {
        log.info("training answer received sessionId={} category={} length={} preview={}",
                sessionId, context.getCategory(), userMessage.length(), preview(userMessage));
        messageStore.append(sessionId, List.of(ChatMessage.user(userMessage)));
        // 回合开始即标记评估中并落库：刷新恢复的前端凭此轮询等待未完成回合
        context.setEvaluating(true);
        sessionStore.save(context);

        sink.progress("正在评估你的回答…");
        AnswerEvaluation evaluation = evaluationService.evaluate(
                context.getCurrentQuestion(), context.getCurrentKnowledgePoint(),
                context.getCurrentCandidateAnswer(), userMessage, true);
        if (forceZeroScore) {
            // 「不知道」：仅综合分强制 0，点评/维度分照常展示
            evaluation = new AnswerEvaluation(evaluation.accuracy(), evaluation.completeness(),
                    evaluation.clarity(), evaluation.depth(), 0,
                    evaluation.keyPoints(), evaluation.missedPoints(), evaluation.wrongPoints(),
                    evaluation.feedback(), evaluation.goodPoints(), evaluation.badPoints(), evaluation.improvedAnswer());
        }
        double overall = evaluation.overall();

        // 导师反馈独立气泡（复用面试训练模式的导师人设），点评全文随回合记录保存供刷新回放
        sink.progress("导师点评中…");
        String comment = streamAndRecordSink(sessionId, mentorPromptBuilder.buildMentorFeedbackMessages(
                messageStore.list(sessionId), context.getCurrentQuestion(), evaluation, context.getStyle()), sink);
        context.getQuestionHistory().add(new TrainingQuestionRecord(
                context.getCurrentQuestion(), context.getCurrentKnowledgePoint(), overall,
                userMessage, comment, evaluation));
        updateScoreStreaks(context, overall);
        // 评分联动：>8 分绿勾、<5 分红叉；「不知道」已预记红叉不重复（forceZeroScore 跳过）
        if (!forceZeroScore) {
            linkMastery(context, overall);
        }
        adjustDifficulty(context, sessionId);
        sink.segment();

        boolean finished = advanceToNextOrFinish(context, sessionId, sink);
        context.setEvaluating(false);
        sessionStore.save(context);
        log.info("training sessionId={} score={} asked={} difficulty={} finished={}",
                sessionId, overall, context.askedCount(), context.getCurrentDifficulty(), finished);
        return new TrainingTurnResult(overall, evaluation.feedback(), finished, statusView(context), evaluation);
    }

    /** 推进下一题或完成：达标题数/题库耗尽则归档完成，否则教练话术出下一题；返回本场是否已完成 */
    private boolean advanceToNextOrFinish(TrainingContext context, String sessionId, InterviewStreamSink sink)
            throws IOException {
        if (context.askedCount() >= properties.getMaxQuestions()) {
            sink.progress("正在生成训练成绩…");
            emitPlain(sessionId, "恭喜！你已完成「" + context.getCategory() + "」专项训练全部 "
                    + properties.getMaxQuestions() + " 题，平均得分 "
                    + oneDecimal(context.averageScore()) + " 分，成绩已归档。", sink);
            finish(context, sessionId);
            return true;
        }
        var next = nextQuestion(context);
        if (next.isEmpty()) {
            emitPlain(sessionId, "「" + context.getCategory() + "」分组的题目已全部练完，本场训练提前完成，"
                    + "平均得分 " + oneDecimal(context.averageScore()) + " 分，成绩已归档。", sink);
            finish(context, sessionId);
            return true;
        }
        applyQuestion(context, next.get());
        sink.progress("正在准备下一题…");
        streamAndRecordSink(sessionId, promptBuilder.buildCoachMessages(
                messageStore.list(sessionId), context.getCategory(),
                context.getCurrentQuestion(), context.askedCount() + 1,
                context.getCurrentDifficulty().label(), context.getStyle()), sink);
        return false;
    }

    /** 评分联动掌握度：高于 8 分加绿勾，低于 5 分加红叉；题库外题目 resolveItemId 返回 empty 天然不记录 */
    private void linkMastery(TrainingContext context, double overall) {
        masteryService.resolveItemId(context.getCurrentQuestion(), context.getUserId())
                .ifPresent(itemId -> {
                    if (overall > 8) {
                        masteryService.recordCheck(context.getUserId(), itemId);
                    } else if (overall < 5) {
                        masteryService.recordCross(context.getUserId(), itemId);
                    }
                });
    }

    /**
     * 主动结束训练：已完成则幂等返回；进行中标记完成并归档已作答成绩。
     */
    public TrainingStatusResponse finishEarly(Long userId, String sessionId) {
        Object lock = lockFor(sessionId);
        synchronized (lock) {
            try {
                TrainingContext context = requireOwnedSession(userId, sessionId);
                if (!context.isFinished()) {
                    finish(context, sessionId);
                    sessionStore.save(context);
                    log.info("training finished early sessionId={} userId={} asked={}",
                            sessionId, userId, context.askedCount());
                }
                return statusView(context);
            } finally {
                releaseLockIfTerminal(sessionId, lock);
            }
        }
    }

    public TrainingStatusResponse status(Long userId, String sessionId) {
        synchronized (lockFor(sessionId)) {
            return statusView(requireOwnedSession(userId, sessionId));
        }
    }

    public List<TrainingRecordView> records(Long userId) {
        return recordRepository.findByUserIdOrderByFinishedAtDesc(userId).stream()
                .map(TrainingRecordView::from)
                .toList();
    }

    /** 我的训练历史（分页）：按完成时间倒序 */
    public Page<TrainingRecordView> records(Long userId, Pageable pageable) {
        return recordRepository.findByUserIdOrderByFinishedAtDesc(userId, pageable)
                .map(TrainingRecordView::from);
    }

    /**
     * 训练报告详情：概要统计 + 逐题明细；仅本人可查（非本人/不存在抛 NOT_FOUND）。
     * 旧归档无明细时 details 为空列表，前端降级为仅展示概要。
     */
    public TrainingReportView getRecordReport(Long userId, Long recordId) {
        TrainingRecord record = recordRepository.findById(recordId)
                .filter(entity -> entity.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "训练记录不存在"));
        long durationMinutes = (record.getFinishedAt().toEpochMilli() - record.getStartTime().toEpochMilli()) / 60_000L;
        return new TrainingReportView(record.getId(), record.getCategory(), record.getStartTime(),
                record.getFinishedAt(), Math.max(0, durationMinutes), record.getAskedCount(),
                record.getAverageScore(), record.getMaxDifficulty(), rating(record.getAverageScore()),
                parseDetails(record));
    }

    /** 评级：与面试报告一致的阈值（>=85 优秀 / >=70 良好 / >=60 及格 / 其余需努力） */
    public String rating(double averageScore) {
        if (averageScore >= 85) {
            return "优秀";
        }
        if (averageScore >= 70) {
            return "良好";
        }
        return averageScore >= 60 ? "及格" : "需努力";
    }

    private List<TrainingQuestionRecord> parseDetails(TrainingRecord record) {
        if (record.getDetailsJson() == null || record.getDetailsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(record.getDetailsJson(),
                    new TypeReference<List<TrainingQuestionRecord>>() {});
        } catch (JsonProcessingException exception) {
            log.error("training record details corrupted recordId={}", record.getId(), exception);
            return List.of();
        }
    }

    /** 按当前难度从该分组取下一道未问题目；当前难度已全部问过则回退任意难度；
     *  题库选题复用面试的可见性查询、连续性策略与掌握度加权 */
    private java.util.Optional<InterviewQuestionBank.InterviewQuestion> nextQuestion(TrainingContext context) {
        Map<Long, Double> weights = masteryService.weightsFor(context.getUserId());
        java.util.Optional<InterviewQuestionBank.InterviewQuestion> next = questionBank.nextQuestion(
                InterviewState.BASICS, context.askedQuestions(),
                context.getCurrentDifficulty(), context.getCategory(), context.askedCount(), null,
                context.getUserId(), List.of(context.getCategory()), weights);
        if (next.isEmpty()) {
            // 当前难度无未问题目（如「已掌握」不升档连续消耗同难度题）：回退任意难度，避免其余难度仍有题时误判枯竭
            next = questionBank.nextQuestion(
                    InterviewState.BASICS, context.askedQuestions(),
                    null, context.getCategory(), context.askedCount(), null,
                    context.getUserId(), List.of(context.getCategory()), weights);
        }
        return next;
    }

    private void applyQuestion(TrainingContext context, InterviewQuestionBank.InterviewQuestion question) {
        context.setCurrentQuestion(question.question());
        context.setCurrentKnowledgePoint(question.knowledgePoint());
        context.setCurrentCandidateAnswer(question.candidateAnswer());
    }

    /** 连续高分升档、连续低分降档（消耗连击，避免逐题连升） */
    private void adjustDifficulty(TrainingContext context, String sessionId) {
        if (context.getConsecutiveHighScores() >= 2 && context.getCurrentDifficulty() != Difficulty.HARD) {
            context.setCurrentDifficulty(context.getCurrentDifficulty().raise());
            context.setConsecutiveHighScores(0);
            log.info("training sessionId={} difficulty raised to {}", sessionId, context.getCurrentDifficulty());
        } else if (context.getConsecutiveLowScores() >= 2 && context.getCurrentDifficulty() != Difficulty.EASY) {
            context.setCurrentDifficulty(context.getCurrentDifficulty().lower());
            context.setConsecutiveLowScores(0);
            log.info("training sessionId={} difficulty lowered to {}", sessionId, context.getCurrentDifficulty());
        }
    }

    private void updateScoreStreaks(TrainingContext context, double overall) {
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

    /** 置完成态并归档简要成绩（幂等由调用方保证） */
    private void finish(TrainingContext context, String sessionId) {
        context.setState(TrainingContext.STATE_FINISHED);
        context.setFinishedAtEpochMillis(System.currentTimeMillis());
        context.setCurrentQuestion(null);
        TrainingRecord record = new TrainingRecord();
        record.setUserId(context.getUserId());
        record.setSessionId(sessionId);
        record.setCategory(context.getCategory());
        record.setAskedCount(context.askedCount());
        record.setAverageScore(Math.round(context.averageScore() * 10 * 10) / 10.0);
        record.setMaxDifficulty(context.getMaxDifficultyReached() == null
                ? Difficulty.EASY.name() : context.getMaxDifficultyReached().name());
        record.setStartTime(Instant.ofEpochMilli(context.getCreatedAtEpochMillis()));
        record.setFinishedAt(Instant.ofEpochMilli(context.getFinishedAtEpochMillis()));
        record.setDetailsJson(serializeDetails(context.getQuestionHistory()));
        recordRepository.save(record);
        messageStore.clear(sessionId);
        log.info("training finished sessionId={} asked={} averageScore={} maxDifficulty={}",
                sessionId, record.getAskedCount(), record.getAverageScore(), record.getMaxDifficulty());
    }

    /** 逐题明细序列化为 JSON 随归档落库；序列化失败不阻断归档（报告页降级为仅概要） */
    private String serializeDetails(List<TrainingQuestionRecord> history) {
        try {
            return objectMapper.writeValueAsString(history);
        } catch (JsonProcessingException exception) {
            log.error("training record details serialization failed", exception);
            return null;
        }
    }

    /** 非 LLM 的固定话术：直接入消息库并推给前端 */
    private void emitPlain(String sessionId, String text, InterviewStreamSink sink) throws IOException {
        messageStore.append(sessionId, List.of(ChatMessage.assistant(text)));
        sink.chunk(text);
    }

    /** 流式生成并落库 assistant 消息，同步推给前端，返回全文（随回合记录保存供刷新回放） */
    private String streamAndRecordSink(String sessionId, List<ChatMessage> messages,
                                       InterviewStreamSink sink) throws IOException {
        StringBuilder fullText = new StringBuilder();
        aiModelClient.generateStream(messages, chunk -> {
            fullText.append(chunk);
            sink.chunk(chunk);
        });
        messageStore.append(sessionId, List.of(ChatMessage.assistant(fullText.toString())));
        return fullText.toString();
    }

    /** 流式生成并落库 assistant 消息，返回全文（start 开场用） */
    private String streamAndRecord(String sessionId, List<ChatMessage> messages) throws IOException {
        StringBuilder fullText = new StringBuilder();
        aiModelClient.generateStream(messages, fullText::append);
        messageStore.append(sessionId, List.of(ChatMessage.assistant(fullText.toString())));
        return fullText.toString();
    }

    private TrainingStatusResponse statusView(TrainingContext context) {
        return TrainingStatusResponse.from(context, properties);
    }

    private TrainingContext requireOwnedSession(Long userId, String sessionId) {
        TrainingContext context = sessionStore.find(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "专项训练会话不存在或已过期"));
        if (context.getUserId() != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限操作该专项训练会话");
        }
        return context;
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private void releaseLockIfTerminal(String sessionId, Object lock) {
        boolean terminal = sessionStore.find(sessionId)
                .map(TrainingContext::isFinished)
                .orElse(true);
        if (terminal) {
            sessionLocks.remove(sessionId, lock);
        }
    }

    private static String oneDecimal(double value) {
        return String.valueOf(Math.round(value * 10) / 10.0);
    }

    /** 日志脱敏：用户回答只保留前 100 字符预览 */
    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= ANSWER_LOG_PREVIEW_LENGTH ? value : value.substring(0, ANSWER_LOG_PREVIEW_LENGTH) + "…";
    }
}
