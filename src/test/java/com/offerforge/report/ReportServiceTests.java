package com.offerforge.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.ai.MockAiModelClient;
import com.offerforge.interview.InterviewContext;
import com.offerforge.interview.InterviewService;
import com.offerforge.interview.InterviewState;
import com.offerforge.interview.QuestionRecord;
import com.offerforge.knowledge.KnowledgeService;
import com.offerforge.knowledge.RetrievedKnowledge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报告生成单元测试：维度/阶段均分统计、亮点与薄弱点识别、推荐材料与知识库关联。
 */
class ReportServiceTests {

    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final ReportService reportService = new ReportService(
            new MockAiModelClient(), knowledgeService,
            mock(InterviewService.class), mock(InterviewSessionRepository.class),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void reportComputesOverallDimensionAndPhaseAverages() {
        // 主问题 8/6/4（追问 3 不计入均分）：主平均 6.0 → 综合分 60
        InterviewContext context = sampleContext();

        InterviewReport report = reportService.generate(context);

        assertThat(report.getInterviewId()).isEqualTo("session-1");
        assertThat(report.getPosition()).isEqualTo("Java 后端工程师");
        assertThat(report.getTotalQuestions()).isEqualTo(3);
        assertThat(report.getTotalFollowUps()).isEqualTo(1);
        assertThat(report.getOverallScore()).isEqualTo(60.0);
        assertThat(report.getRating()).isEqualTo("及格");
        assertThat(report.getAvgAccuracy()).isEqualTo(6.0);
        assertThat(report.getAvgCompleteness()).isEqualTo(6.0);
        assertThat(report.getAvgClarity()).isEqualTo(6.0);
        assertThat(report.getAvgDepth()).isEqualTo(6.0);
        // 各阶段分 = 该阶段主问题平均
        assertThat(report.getBasicsScore()).isEqualTo(8.0);
        assertThat(report.getProjectScore()).isEqualTo(6.0);
        assertThat(report.getDeepScore()).isEqualTo(4.0);
        // 逐题点评含追问条目
        assertThat(report.getQuestionEvaluations()).hasSize(4);
        assertThat(report.getQuestionEvaluations().get(3).followUp()).isTrue();
    }

    @Test
    void identifiesStrengthsAndWeaknessesFromScores() {
        InterviewContext context = sampleContext();

        InterviewReport report = reportService.generate(context);

        // Mock 摘要取最高/最低分题：最高「HashMap 的底层原理？」8 分，最低「线程池参数如何设置？」4 分
        assertThat(String.join("", report.getStrengths())).contains("HashMap 的底层原理？");
        assertThat(String.join("", report.getWeaknesses())).contains("线程池参数如何设置？");
        assertThat(report.getSuggestions()).isNotEmpty();
    }

    @Test
    void recommendedMaterialsLinkWeakKnowledgePointsToKnowledgeBase() {
        when(knowledgeService.search("线程池参数", 1)).thenReturn(List.of(
                new RetrievedKnowledge(1L, "线程池的核心参数有哪些，如何设置？", "答案", "并发", 0.9)));
        InterviewContext context = sampleContext();

        InterviewReport report = reportService.generate(context);

        // 薄弱题（<6 分）只有「线程池参数如何设置？」，对应知识点关联到知识库练习题
        assertThat(report.getRecommendedMaterials()).hasSize(1);
        RecommendedMaterial material = report.getRecommendedMaterials().get(0);
        assertThat(material.topic()).isEqualTo("线程池参数");
        assertThat(material.suggestedQuestion()).isEqualTo("线程池的核心参数有哪些，如何设置？");
        assertThat(material.reason()).contains("得分偏低");
    }

    @Test
    void recommendedMaterialFallsBackToWeakQuestionWhenSearchEmpty() {
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(List.of());
        InterviewContext context = sampleContext();

        InterviewReport report = reportService.generate(context);

        assertThat(report.getRecommendedMaterials()).hasSize(1);
        assertThat(report.getRecommendedMaterials().get(0).suggestedQuestion())
                .isEqualTo("线程池参数如何设置？");
    }

    @Test
    void ratingBoundaries() {
        assertThat(reportService.rating(85.0)).isEqualTo("优秀");
        assertThat(reportService.rating(84.9)).isEqualTo("良好");
        assertThat(reportService.rating(70.0)).isEqualTo("良好");
        assertThat(reportService.rating(69.9)).isEqualTo("及格");
        assertThat(reportService.rating(60.0)).isEqualTo("及格");
        assertThat(reportService.rating(59.9)).isEqualTo("需努力");
    }

    @Test
    void weakQuestionsUsesLowestTwoWhenNoScoreBelowThreshold() {
        // 全部 >=6 分时，薄弱点取最低 2 题兜底
        InterviewContext context = newContext();
        answer(context, InterviewState.BASICS, "问题A", "知识点A", 8.0, false);
        answer(context, InterviewState.BASICS, "问题B", "知识点B", 7.0, false);
        answer(context, InterviewState.DEEP, "问题C", "知识点C", 6.0, false);

        List<QuestionRecord> weak = reportService.weakQuestions(
                context.getQuestionHistory().stream().filter(r -> !r.isFollowUp()).toList());

        assertThat(weak).extracting(QuestionRecord::getQuestion)
                .containsExactly("问题C", "问题B");
    }

    /** 3 主问题（8/6/4）+ 1 追问（3）的样例会话 */
    private InterviewContext sampleContext() {
        InterviewContext context = newContext();
        answer(context, InterviewState.BASICS, "HashMap 的底层原理？", "HashMap 原理", 8.0, false);
        answer(context, InterviewState.PROJECT, "介绍一下你做过的项目", "", 6.0, false);
        answer(context, InterviewState.DEEP, "线程池参数如何设置？", "线程池参数", 4.0, false);
        answer(context, InterviewState.DEEP, "关于「线程池参数如何设置？」，换个角度举例说明", "线程池参数", 3.0, true);
        return context;
    }

    private InterviewContext newContext() {
        InterviewContext context = new InterviewContext();
        context.setSessionId("session-1");
        context.setUserId(1L);
        context.setPosition("Java 后端工程师");
        context.setCreatedAtEpochMillis(System.currentTimeMillis());
        return context;
    }

    private void answer(InterviewContext context, InterviewState phase, String question,
                        String knowledgePoint, double score, boolean followUp) {
        context.setCurrentQuestionPhase(phase);
        context.setCurrentKnowledgePoint(knowledgePoint);
        context.setCurrentQuestionFollowUp(followUp);
        AnswerEvaluation evaluation = new AnswerEvaluation(score, score, score, score, score,
                List.of("核心要点"), List.of(), List.of(), "点评", null, null, null);
        context.recordAnswer(question, "回答：" + question, evaluation);
    }
}
