package com.offerforge.interview;

import com.offerforge.ai.AiGeneratedQuestion;
import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.knowledge.Difficulty;
import com.offerforge.resume.ProjectExperience;
import com.offerforge.resume.ResumeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectQuestionGenerator 单测：项目选择、Prompt 构造、LLM 结果映射与深挖题生成。
 */
class ProjectQuestionGeneratorTests {

    private final ResumeService resumeService = mock(ResumeService.class);
    private final AiModelClient aiModelClient = mock(AiModelClient.class);
    private final ProjectQuestionGenerator generator = new ProjectQuestionGenerator(resumeService, aiModelClient);

    @Test
    void generatesProjectQuestionsFromRichestTechStackProject() {
        when(resumeService.getProjects(1L, 9L)).thenReturn(List.of(
                new ProjectExperience("个人博客", null, null, null, "Java", null, null),
                new ProjectExperience("秒杀系统", "后端负责人", "2025", "高并发", "Spring Boot, Redis, MQ, MySQL", "10w QPS", "超卖"),
                new ProjectExperience("后台管理", null, null, null, "Vue, Java", null, null)));
        when(aiModelClient.generateProjectQuestions(anyString())).thenReturn(List.of(
                new AiGeneratedQuestion("请介绍秒杀系统的整体架构", "项目架构", List.of("要点1", "要点2"), "MEDIUM"),
                new AiGeneratedQuestion("超卖如何解决", "技术难点", List.of("要点3"), "HARD")));

        List<InterviewQuestionBank.InterviewQuestion> questions = generator.generateProjectQuestions(1L, 9L);

        // 选中技术栈最丰富的「秒杀系统」并传入 Prompt
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelClient).generateProjectQuestions(prompt.capture());
        assertThat(prompt.getValue()).contains("项目名称：秒杀系统").contains("技术栈：Spring Boot, Redis, MQ, MySQL");

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).question()).isEqualTo("请介绍秒杀系统的整体架构");
        assertThat(questions.get(0).knowledgePoint()).isEqualTo("项目经历 · 秒杀系统");
        assertThat(questions.get(0).candidateAnswer()).isEqualTo("要点1\n要点2");
        assertThat(questions.get(0).difficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(questions.get(1).difficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    void projectQuestionsEmptyWithoutProjectsOrLlmResult() {
        when(resumeService.getProjects(1L, 9L)).thenReturn(List.of());
        assertThat(generator.generateProjectQuestions(1L, 9L)).isEmpty();

        when(resumeService.getProjects(1L, 10L)).thenReturn(List.of(
                new ProjectExperience("秒杀系统", null, null, null, "Java", null, null)));
        when(aiModelClient.generateProjectQuestions(anyString())).thenReturn(List.of());
        assertThat(generator.generateProjectQuestions(1L, 10L)).isEmpty();
    }

    @Test
    void generatesDeepQuestionsFromLowestScoreProjectAnswerFirst() {
        InterviewContext context = new InterviewContext();
        context.getQuestionHistory().add(projectRecord("请介绍秒杀系统的整体架构",
                "回答得不错，架构讲得很清楚", 8.0, List.of()));
        context.getQuestionHistory().add(projectRecord("超卖如何解决",
                "不太清楚", 3.0, List.of("分布式锁", "库存预扣减")));

        when(aiModelClient.generateDeepQuestion(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            return new AiGeneratedQuestion("深挖：" + (prompt.contains("超卖") ? "库存扣减细节" : "架构权衡"),
                    "项目深挖", List.of("要点"), "HARD");
        });

        List<InterviewQuestionBank.InterviewQuestion> questions = generator.generateDeepQuestions(context, 2);

        // 低分题（超卖，3 分）优先生成深挖；Prompt 携带原题、回答、评分与遗漏要点
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(aiModelClient, org.mockito.Mockito.times(2)).generateDeepQuestion(prompts.capture());
        assertThat(prompts.getAllValues().get(0))
                .contains("问题：超卖如何解决")
                .contains("候选人回答：不太清楚")
                .contains("回答评分：3.0")
                .contains("分布式锁");

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).question()).isEqualTo("深挖：库存扣减细节");
        assertThat(questions.get(0).knowledgePoint()).isEqualTo("项目深挖");
        assertThat(questions.get(0).difficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    void deepQuestionsSkipNullGenerationAndEmptyHistory() {
        InterviewContext empty = new InterviewContext();
        assertThat(generator.generateDeepQuestions(empty, 3)).isEmpty();

        InterviewContext context = new InterviewContext();
        context.getQuestionHistory().add(projectRecord("问题A", "回答A", 5.0, List.of()));
        when(aiModelClient.generateDeepQuestion(anyString())).thenReturn(null);
        assertThat(generator.generateDeepQuestions(context, 3)).isEmpty();
    }

    @Test
    void deepPromptCarriesProjectNameAndParseProjectNameRoundTrips() {
        assertThat(ProjectQuestionGenerator.parseProjectName("项目经历 · 秒杀系统")).isEqualTo("秒杀系统");
        assertThat(ProjectQuestionGenerator.parseProjectName("项目经历")).isEqualTo("项目经历");
        assertThat(ProjectQuestionGenerator.parseProjectName(null)).isEqualTo("项目经历");

        InterviewContext context = new InterviewContext();
        context.getQuestionHistory().add(projectRecord("问题A", "回答A", 5.0, List.of()));
        when(aiModelClient.generateDeepQuestion(anyString()))
                .thenReturn(new AiGeneratedQuestion("深挖题", "", List.of(), null));

        List<InterviewQuestionBank.InterviewQuestion> questions = generator.generateDeepQuestions(context, 1);

        // LLM 未给知识点时回退「项目经历 · 项目名」；难度缺省 HARD
        assertThat(questions.get(0).knowledgePoint()).isEqualTo("项目经历 · 秒杀系统");
        assertThat(questions.get(0).difficulty()).isEqualTo(Difficulty.HARD);
    }

    private QuestionRecord projectRecord(String question, String answer, double score, List<String> missedPoints) {
        AnswerEvaluation evaluation = new AnswerEvaluation(score, score, score, score, score,
                List.of(), missedPoints, List.of(), "点评", null, null, null);
        return new QuestionRecord(question, answer, evaluation, "项目经历 · 秒杀系统", false, InterviewState.PROJECT);
    }
}
