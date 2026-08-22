package com.offerforge.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.LlmCallContext;
import com.offerforge.ai.LlmCredentialResolver;
import com.offerforge.ai.ReportSummary;
import com.offerforge.billing.BillingMeteringService;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.interview.InterviewContext;
import com.offerforge.interview.InterviewService;
import com.offerforge.interview.InterviewState;
import com.offerforge.interview.QuestionRecord;
import com.offerforge.knowledge.KnowledgeService;
import com.offerforge.knowledge.RetrievedKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * 综合报告生成与归档：评分统计（综合分/维度均分/阶段均分）全部由服务端计算，
 * 亮点/薄弱点/建议文字由模型生成、解析失败时服务端兜底，推荐材料按薄弱知识点从知识库检索。
 * 报告以 JSON 归档到 interview_session 表，查询接口从归档记录反序列化。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    /** 薄弱题阈值：低于该分的主问题纳入薄弱点与推荐材料 */
    private static final double WEAK_THRESHOLD = 6.0;
    private static final int TOP_N = 2;
    private static final int MAX_MATERIAL_TOPICS = 3;
    private static final int MAX_PROGRESS_LIMIT = 50;

    private final AiModelClient aiModelClient;
    private final KnowledgeService knowledgeService;
    private final InterviewService interviewService;
    private final InterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final LlmCredentialResolver credentialResolver;
    private final BillingMeteringService billingMeteringService;

    public ReportService(AiModelClient aiModelClient,
                         KnowledgeService knowledgeService,
                         InterviewService interviewService,
                         InterviewSessionRepository sessionRepository,
                         ObjectMapper objectMapper,
                         LlmCredentialResolver credentialResolver,
                         BillingMeteringService billingMeteringService) {
        this.aiModelClient = aiModelClient;
        this.knowledgeService = knowledgeService;
        this.interviewService = interviewService;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
        this.billingMeteringService = billingMeteringService;
    }

    /**
     * 结束面试结果：有效场次携带归档报告（archived=true）；
     * 问答次数不足计次门槛的无效场次不记录历史，report 为 null 且 archived=false。
     */
    public record FinishOutcome(InterviewReport report, boolean archived) {
    }

    /**
     * 结束面试并生成报告归档；重复调用幂等，已归档的会话直接返回既有报告。
     * 问答次数不足计次门槛的场次不消耗免费额度（结束时退还）且不记录历史。
     */
    public FinishOutcome finishAndArchive(Long userId, String sessionId) {
        InterviewSession existing = sessionRepository.findByUserIdAndSessionId(userId, sessionId).orElse(null);
        if (existing != null) {
            return new FinishOutcome(parseReport(existing), true);
        }
        InterviewContext context = interviewService.finishInterview(userId, sessionId);
        if (context.totalQuestionsAsked() < interviewService.minBillableQuestions()) {
            log.info("short session skipped archive sessionId={} userId={} asked={} threshold={}",
                    sessionId, userId, context.totalQuestionsAsked(), interviewService.minBillableQuestions());
            return new FinishOutcome(null, false);
        }
        InterviewReport report = generate(context);
        InterviewSession entity = new InterviewSession();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setPosition(report.getPosition());
        // 归档面试模式（training/practice，context 已归一化），历史列表据此划分展示
        entity.setMode(context.getMode());
        entity.setStartTime(report.getInterviewTime());
        entity.setEndTime(Instant.now());
        entity.setStatus(InterviewState.FINISHED.name());
        entity.setOverallScore(report.getOverallScore());
        entity.setReportJson(serialize(report));
        sessionRepository.save(entity);
        return new FinishOutcome(report, true);
    }

    /**
     * 查询单次面试报告；按用户 + 会话 id 限定归属，非本人或不存在时抛 NOT_FOUND。
     */
    public InterviewReport getReport(Long userId, String interviewId) {
        InterviewSession entity = sessionRepository.findByUserIdAndSessionId(userId, interviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试报告不存在"));
        return parseReport(entity);
    }

    /**
     * 历史面试列表：按开始时间倒序分页；mode 非空时仅返回该模式（training/practice）记录。
     */
    public Page<InterviewHistoryItem> history(Long userId, String mode, Pageable pageable) {
        Page<InterviewSession> page = mode == null || mode.isBlank()
                ? sessionRepository.findByUserIdOrderByStartTimeDesc(userId, pageable)
                : sessionRepository.findByUserIdAndModeOrderByStartTimeDesc(userId, mode, pageable);
        return page.map(this::toHistoryItem);
    }

    public Page<InterviewHistoryItem> history(Long userId, Pageable pageable) {
        return history(userId, null, pageable);
    }

    private InterviewHistoryItem toHistoryItem(InterviewSession entity) {
        // 存量记录 mode 列默认 practice，反序列化后空值同样归为实战模式
        String mode = entity.getMode() == null || entity.getMode().isBlank() ? "practice" : entity.getMode();
        return new InterviewHistoryItem(entity.getSessionId(), entity.getPosition(), mode,
                entity.getStartTime(), entity.getOverallScore(), entity.getStatus());
    }

    /**
     * 进步曲线：最近 limit 次面试的综合分（含模式标识），按开始时间正序返回。
     */
    public List<InterviewProgressPoint> progress(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_PROGRESS_LIMIT));
        List<InterviewSession> latest = sessionRepository
                .findByUserIdOrderByStartTimeDesc(userId, PageRequest.of(0, capped))
                .getContent();
        List<InterviewProgressPoint> points = new ArrayList<>(latest.stream()
                .map(entity -> new InterviewProgressPoint(entity.getSessionId(),
                        entity.getStartTime(), entity.getOverallScore(),
                        entity.getMode() == null || entity.getMode().isBlank() ? "practice" : entity.getMode()))
                .toList());
        // 倒序查出后反转为时间正序，便于前端直接绘制趋势
        Collections.reverse(points);
        return points;
    }

    private InterviewReport parseReport(InterviewSession entity) {
        try {
            return objectMapper.readValue(entity.getReportJson(), InterviewReport.class);
        } catch (JsonProcessingException exception) {
            log.error("report json corrupted sessionId={}", entity.getSessionId(), exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告数据异常，请联系管理员");
        }
    }

    private String serialize(InterviewReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException exception) {
            log.error("report serialization failed interviewId={}", report.getInterviewId(), exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告生成失败，请稍后重试");
        }
    }

    /**
     * 基于会话工作记忆生成完整报告；只统计主问题（追问不计入均分）；
     * 开场自我介绍记录仅展示不入报告，统计与逐题评估均排除。
     */
    public InterviewReport generate(InterviewContext context) {
        List<QuestionRecord> history = context.getQuestionHistory();
        List<QuestionRecord> effective = history.stream()
                .filter(record -> record.getState() != InterviewState.OPENING).toList();
        List<QuestionRecord> mains = effective.stream().filter(record -> !record.isFollowUp()).toList();

        InterviewReport report = new InterviewReport();
        report.setInterviewId(context.getSessionId());
        report.setUserId(context.getUserId());
        report.setInterviewTime(Instant.ofEpochMilli(context.getCreatedAtEpochMillis()));
        report.setPosition(context.getPosition() == null || context.getPosition().isBlank()
                ? InterviewService.DEFAULT_POSITION : context.getPosition());
        report.setTotalQuestions(mains.size());
        report.setTotalFollowUps(effective.size() - mains.size());
        report.setDurationMinutes((int) ((System.currentTimeMillis() - context.getCreatedAtEpochMillis()) / 60_000L));

        report.setOverallScore(round1(mainAverage(mains, QuestionRecord::getScore) * 10));
        report.setRating(rating(report.getOverallScore()));
        report.setAvgAccuracy(round1(mainAverage(mains, QuestionRecord::getAccuracy)));
        report.setAvgCompleteness(round1(mainAverage(mains, QuestionRecord::getCompleteness)));
        report.setAvgClarity(round1(mainAverage(mains, QuestionRecord::getClarity)));
        report.setAvgDepth(round1(mainAverage(mains, QuestionRecord::getDepth)));
        report.setBasicsScore(round1(phaseAverage(mains, InterviewState.BASICS)));
        report.setProjectScore(round1(phaseAverage(mains, InterviewState.PROJECT)));
        report.setDeepScore(round1(phaseAverage(mains, InterviewState.DEEP)));

        List<QuestionEvaluation> evaluations = new ArrayList<>();
        for (int index = 0; index < effective.size(); index++) {
            QuestionRecord record = effective.get(index);
            evaluations.add(new QuestionEvaluation(index + 1, record.getQuestion(), record.getUserAnswer(),
                    record.getScore(), record.getState(), record.isFollowUp(), record.getFeedback()));
        }
        report.setQuestionEvaluations(evaluations);

        List<QuestionRecord> weakQuestions = weakQuestions(mains);
        fillSummary(report, context, mains, weakQuestions);
        report.setRecommendedMaterials(buildMaterials(context.getUserId(), weakQuestions));
        log.info("report generated sessionId={} questions={} overallScore={} rating={}",
                context.getSessionId(), report.getTotalQuestions(), report.getOverallScore(), report.getRating());
        return report;
    }

    /**
     * 评级：>=85 优秀 / >=70 良好 / >=60 及格 / 其余需努力。
     */
    public String rating(double overallScore) {
        if (overallScore >= 85) {
            return "优秀";
        }
        if (overallScore >= 70) {
            return "良好";
        }
        return overallScore >= 60 ? "及格" : "需努力";
    }

    private void fillSummary(InterviewReport report, InterviewContext context,
                             List<QuestionRecord> mains, List<QuestionRecord> weakQuestions) {
        ReportSummary summary;
        if (mains.isEmpty()) {
            // 无任何有效作答（综合分 0）：不调 LLM，直接给出低分场景措辞，
            // 避免模型基于空记录编造“表现稳定”类反馈
            summary = emptySessionSummary();
        } else {
            summary = null;
            // 计费场次：报告总结同口径纳入计量，绑定本场凭据与用量监听，结束后必须清理防线程复用泄漏
            boolean billable = context.isBillable();
            if (billable) {
                LlmCallContext.bind(credentialResolver.resolveFor(context.getUserId(), context.getSelectedModel()));
                LlmCallContext.setUsageListener((inputTokens, outputTokens) -> billingMeteringService.recordUsage(
                        context.getUserId(), context.getSelectedModel(), inputTokens, outputTokens));
            }
            try {
                summary = aiModelClient.generateReportSummary(buildSummaryPrompt(context, mains));
            } catch (Exception exception) {
                log.warn("report summary generation failed, fallback to server-side summary: {}", exception.getMessage());
            } finally {
                if (billable) {
                    LlmCallContext.clear();
                }
            }
            if (summary == null) {
                summary = fallbackSummary(mains, weakQuestions);
            }
        }
        report.setStrengths(summary.strengths());
        report.setWeaknesses(summary.weaknesses());
        report.setSuggestions(summary.suggestions());
    }

    /** 无有效作答场次的报告措辞：不点评亮点，引导用户完成足够作答后再评估 */
    private ReportSummary emptySessionSummary() {
        return new ReportSummary(List.of(),
                List.of("本次面试没有产生有效作答，无法评估你当前的真实水平"),
                List.of("建议至少完整作答 3 道题再结束面试，报告才能定位你的薄弱知识点",
                        "可先在资料库巩固相关知识，再回来进行针对性模拟"));
    }

    String buildSummaryPrompt(InterviewContext context, List<QuestionRecord> mains) {
        StringBuilder records = new StringBuilder();
        for (QuestionRecord record : mains) {
            records.append("- 「").append(record.getQuestion()).append("」得分").append(record.getScore())
                    .append("（").append(record.getState() == null ? "未知阶段" : record.getState().label()).append("）\n");
        }
        long followUps = context.getQuestionHistory().stream()
                .filter(record -> record.getState() != InterviewState.OPENING)
                .count() - mains.size();
        return "<task>report-summary</task>\n"
                + "你是一个资深技术面试官，请根据以下面试记录生成综合反馈报告。\n\n"
                + "面试岗位：" + context.getPosition() + "\n"
                + "总题数：" + mains.size() + "\n"
                + "总追问次数：" + followUps + "\n\n"
                + "各题评估记录：\n" + records + "\n"
                + "请生成报告，包含：\n"
                + "1. 亮点总结（2-3条，基于表现好的回答）\n"
                + "2. 薄弱点总结（2-3条，基于表现差的回答）\n"
                + "3. 改进建议（3-5条，针对薄弱点给出具体可操作的建议）\n\n"
                + "请返回 JSON 格式：{\"strengths\": [\"...\"], \"weaknesses\": [\"...\"], \"suggestions\": [\"...\"]}\n"
                + "只输出 JSON 本身，不要输出其他内容。";
    }

    private ReportSummary fallbackSummary(List<QuestionRecord> mains, List<QuestionRecord> weakQuestions) {
        if (mains.isEmpty()) {
            return emptySessionSummary();
        }
        List<QuestionRecord> ranked = mains.stream()
                .sorted(Comparator.comparingDouble(QuestionRecord::getScore).reversed())
                .toList();
        List<String> strengths = new ArrayList<>();
        for (QuestionRecord record : ranked.stream().limit(TOP_N).toList()) {
            strengths.add("「" + record.getQuestion() + "」回答较好（得分" + record.getScore() + "），要点覆盖完整");
        }
        List<String> weaknesses = new ArrayList<>();
        for (QuestionRecord record : weakQuestions) {
            weaknesses.add("「" + record.getQuestion() + "」回答薄弱（得分" + record.getScore()
                    + "），知识点「" + record.getKnowledgePoint() + "」需重点复习");
        }
        if (weaknesses.isEmpty()) {
            weaknesses.add("整体表现稳定，可进一步挑战更高难度题目");
        }
        return new ReportSummary(strengths, weaknesses, List.of(
                "针对薄弱知识点做专题练习，优先补齐遗漏要点",
                "回答时先给结论再展开原理，并结合实际场景举例",
                "对高频考点做深度复盘，关注原理与工程实践的结合"));
    }

    /**
     * 薄弱题：<6 分的主问题；若无则取最低分 2 题（有作答即有薄弱参考）。
     */
    List<QuestionRecord> weakQuestions(List<QuestionRecord> mains) {
        List<QuestionRecord> weak = mains.stream()
                .filter(record -> record.getScore() < WEAK_THRESHOLD)
                .sorted(Comparator.comparingDouble(QuestionRecord::getScore))
                .toList();
        if (!weak.isEmpty()) {
            return weak.stream().limit(TOP_N + 1).toList();
        }
        return mains.stream()
                .sorted(Comparator.comparingDouble(QuestionRecord::getScore))
                .limit(TOP_N)
                .toList();
    }

    /**
     * 推荐材料：薄弱知识点去重后从知识库检索练习题（仅官方 + 本人私有）；检索为空时用薄弱题本身兜底。
     */
    private List<RecommendedMaterial> buildMaterials(Long userId, List<QuestionRecord> weakQuestions) {
        Set<String> topics = new LinkedHashSet<>();
        for (QuestionRecord record : weakQuestions) {
            if (record.getKnowledgePoint() != null && !record.getKnowledgePoint().isBlank()
                    && topics.size() < MAX_MATERIAL_TOPICS) {
                topics.add(record.getKnowledgePoint());
            }
        }
        List<RecommendedMaterial> materials = new ArrayList<>();
        for (String topic : topics) {
            QuestionRecord related = weakQuestions.stream()
                    .filter(record -> topic.equals(record.getKnowledgePoint()))
                    .findFirst()
                    .orElse(null);
            String suggested = null;
            try {
                List<RetrievedKnowledge> results = knowledgeService.search(userId, topic, 1);
                if (!results.isEmpty()) {
                    suggested = results.get(0).question();
                }
            } catch (Exception exception) {
                log.warn("report material search failed topic={}: {}", topic, exception.getMessage());
            }
            if (suggested == null && related != null) {
                suggested = related.getQuestion();
            }
            double score = related == null ? 0 : related.getScore();
            materials.add(new RecommendedMaterial(topic,
                    "该知识点相关题目得分偏低（" + score + " 分），建议针对性强化", suggested));
        }
        return materials;
    }

    private double mainAverage(List<QuestionRecord> mains, ToDoubleFunction<QuestionRecord> selector) {
        return mains.stream().mapToDouble(selector).average().orElse(0.0);
    }

    private double phaseAverage(List<QuestionRecord> mains, InterviewState phase) {
        return mains.stream()
                .filter(record -> record.getState() == phase)
                .mapToDouble(QuestionRecord::getScore)
                .average()
                .orElse(0.0);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
