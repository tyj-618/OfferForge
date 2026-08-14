package com.offerforge.interview;

import com.offerforge.ai.AiGeneratedQuestion;
import com.offerforge.ai.AiModelClient;
import com.offerforge.knowledge.Difficulty;
import com.offerforge.resume.ProjectExperience;
import com.offerforge.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 项目题与深挖题生成：
 * <ul>
 *   <li>PROJECT 阶段：从简历项目经历中选技术栈最丰富的项目，调 LLM 生成 2-3 个问题</li>
 *   <li>DEEP 阶段：基于 PROJECT 阶段的问题与回答（低分优先）生成深挖题</li>
 * </ul>
 * 生成失败或无简历时由调用方降级到通用题库。
 */
@Component
public class ProjectQuestionGenerator {

    private static final Logger log = LoggerFactory.getLogger(ProjectQuestionGenerator.class);
    /** 简历项目题知识点前缀：「项目经历 · 项目名」，报告与深挖均靠它关联项目 */
    public static final String PROJECT_KNOWLEDGE_PREFIX = "项目经历 · ";
    private static final String PROJECT_KNOWLEDGE_POINT = "项目经历";

    private final ResumeService resumeService;
    private final AiModelClient aiModelClient;

    public ProjectQuestionGenerator(ResumeService resumeService, AiModelClient aiModelClient) {
        this.resumeService = resumeService;
        this.aiModelClient = aiModelClient;
    }

    /**
     * 基于简历生成项目题：选技术栈最丰富的项目（并列取最近/首个），生成失败返回空列表。
     */
    public List<InterviewQuestionBank.InterviewQuestion> generateProjectQuestions(Long userId, Long resumeId) {
        List<ProjectExperience> projects = resumeService.getProjects(userId, resumeId);
        if (projects.isEmpty()) {
            log.info("project question generation skipped: resume {} has no projects", resumeId);
            return List.of();
        }
        ProjectExperience chosen = selectProject(projects);
        List<AiGeneratedQuestion> generated = aiModelClient.generateProjectQuestions(buildProjectPrompt(chosen));
        String knowledgePoint = PROJECT_KNOWLEDGE_PREFIX + projectName(chosen);
        return generated.stream()
                .map(question -> new InterviewQuestionBank.InterviewQuestion(
                        question.question(),
                        String.join("\n", question.referenceAnswer()),
                        knowledgePoint,
                        parseDifficulty(question.difficulty(), Difficulty.MEDIUM)))
                .toList();
    }

    /**
     * 基于 PROJECT 阶段作答记录生成深挖题：低分题优先，最多 needed 道；无项目作答记录返回空列表。
     */
    public List<InterviewQuestionBank.InterviewQuestion> generateDeepQuestions(InterviewContext context, int needed) {
        List<QuestionRecord> projectRecords = context.getQuestionHistory().stream()
                .filter(record -> record.getState() == InterviewState.PROJECT && !record.isFollowUp())
                .sorted(Comparator.comparingDouble(QuestionRecord::getScore))
                .limit(Math.max(0, needed))
                .toList();
        List<InterviewQuestionBank.InterviewQuestion> questions = new ArrayList<>();
        for (QuestionRecord record : projectRecords) {
            String projectName = parseProjectName(record.getKnowledgePoint());
            AiGeneratedQuestion generated = aiModelClient.generateDeepQuestion(
                    buildDeepPrompt(projectName, record.getQuestion(), record.getUserAnswer(),
                            record.getScore(), record.getMissedPoints()));
            if (generated != null) {
                questions.add(new InterviewQuestionBank.InterviewQuestion(
                        generated.question(),
                        String.join("\n", generated.referenceAnswer()),
                        generated.knowledgePoint() == null || generated.knowledgePoint().isBlank()
                                ? PROJECT_KNOWLEDGE_PREFIX + projectName
                                : generated.knowledgePoint(),
                        parseDifficulty(generated.difficulty(), Difficulty.HARD)));
            }
        }
        return questions;
    }

    /**
     * 选择项目：技术栈最丰富（分隔符切分后词条最多）者优先，并列取列表中靠前的（约定最近的在前）。
     */
    ProjectExperience selectProject(List<ProjectExperience> projects) {
        return projects.stream()
                .max(Comparator.comparingInt(this::techStackRichness))
                .orElse(projects.get(0));
    }

    private int techStackRichness(ProjectExperience project) {
        String techStack = project.techStack();
        if (techStack == null || techStack.isBlank()) {
            return 0;
        }
        return techStack.split("[,，、/;；\\s]+").length;
    }

    private String projectName(ProjectExperience project) {
        return project.projectName() == null || project.projectName().isBlank() ? "未命名项目" : project.projectName().trim();
    }

    String buildProjectPrompt(ProjectExperience project) {
        return """
                你是一个技术面试官，正在面试一位候选人。以下是候选人的项目经历：

                项目名称：%s
                项目描述：%s
                技术栈：%s
                候选人角色：%s
                项目亮点：%s

                请根据这个项目生成 2-3 个面试问题，要求：
                1. 第一个问题围绕项目整体架构或设计思路（宏观）
                2. 第二个问题深入某个技术细节或难点（微观）
                3. 第三个问题考察候选人在项目中的思考和成长（反思）
                4. 问题要具体，不要泛泛而谈
                5. 用中文提问
                6. 每个问题附带标准答案要点（3-5 个关键要点）

                请返回 JSON 格式：
                {
                  "questions": [
                    {
                      "question": "问题内容",
                      "knowledgePoint": "考察的知识点",
                      "referenceAnswer": ["要点1", "要点2", "要点3"],
                      "difficulty": "EASY/MEDIUM/HARD"
                    }
                  ]
                }
                """.formatted(
                projectName(project),
                orDefault(project.description()),
                orDefault(project.techStack()),
                orDefault(project.role()),
                orDefault(project.highlights()));
    }

    String buildDeepPrompt(String projectName, String question, String userAnswer,
                           double score, List<String> missedPoints) {
        return """
                候选人在项目"%s"中回答了以下问题：

                问题：%s
                候选人回答：%s
                回答评分：%s
                遗漏的要点：%s

                请生成一个深挖问题，要求：
                1. 基于候选人的回答进行追问，而不是重复原问题
                2. 如果回答中有遗漏或错误，针对这些点追问
                3. 如果回答较好，追问更深层的实现细节或设计权衡
                4. 考察候选人是否真正理解自己做的项目
                5. 用中文提问
                6. 附带标准答案要点

                请返回 JSON 格式：
                {
                  "question": "深挖问题内容",
                  "knowledgePoint": "考察的知识点",
                  "referenceAnswer": ["要点1", "要点2", "要点3"],
                  "difficulty": "HARD"
                }
                """.formatted(
                projectName,
                question,
                userAnswer == null || userAnswer.isBlank() ? "（未作答）" : userAnswer,
                score,
                missedPoints == null || missedPoints.isEmpty() ? "（无）" : String.join("；", missedPoints));
    }

    /** 从知识点「项目经历 · 项目名」解析项目名；通用项目题回退为「项目经历」 */
    static String parseProjectName(String knowledgePoint) {
        if (knowledgePoint != null && knowledgePoint.startsWith(PROJECT_KNOWLEDGE_PREFIX)) {
            return knowledgePoint.substring(PROJECT_KNOWLEDGE_PREFIX.length()).trim();
        }
        return PROJECT_KNOWLEDGE_POINT;
    }

    private Difficulty parseDifficulty(String difficulty, Difficulty fallback) {
        if (difficulty == null || difficulty.isBlank()) {
            return fallback;
        }
        try {
            return Difficulty.valueOf(difficulty.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private String orDefault(String value) {
        return value == null || value.isBlank() ? "（未提供）" : value;
    }
}
