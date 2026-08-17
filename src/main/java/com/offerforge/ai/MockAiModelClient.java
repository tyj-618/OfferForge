package com.offerforge.ai;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Conditional(AiClientConditions.MockAiModel.class)
public class MockAiModelClient implements AiModelClient {

    private static final Pattern QUESTION_PATTERN = Pattern.compile("<question>\\s*(.*?)\\s*</question>", Pattern.DOTALL);
    private static final Pattern FOLLOW_UP_SOURCE_PATTERN = Pattern.compile("对问题「(.*?)」的回答不够理想", Pattern.DOTALL);
    private static final Pattern REPORT_SCORE_PATTERN = Pattern.compile("「(.*?)」得分([0-9.]+)", Pattern.DOTALL);
    private static final Pattern REF_PATTERN = Pattern.compile("\\[ref: (\\d+)]");
    private static final Pattern RESUME_NAME_PATTERN = Pattern.compile("姓名[:：]\\s*([^\\s，,。;；]+)");
    private static final Pattern RESUME_PROJECT_PATTERN = Pattern.compile("项目名称[:：]\\s*(.+)");
    private static final Pattern RESUME_TECH_PATTERN = Pattern.compile("技术栈[:：]\\s*(.+)");
    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("项目名称[:：]\\s*(.+)");
    private static final Pattern DEEP_QUESTION_PATTERN = Pattern.compile("问题[:：]\\s*(.+)");
    private static final int STREAM_CHUNK_SIZE = 8;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<List<ChatMessage>> lastGeneratedMessages = new AtomicReference<>(List.of());

    @Override
    public AiTextResult generateText(List<ChatMessage> messages) {
        List<ChatMessage> copiedMessages = List.copyOf(messages);
        lastGeneratedMessages.set(copiedMessages);
        String answer = mockAnswer(lastUserContent(copiedMessages));
        return new AiTextResult(answer, UUID.randomUUID().toString(), 0, 0);
    }

    @Override
    public void generateStream(List<ChatMessage> messages, AiStreamChunkConsumer chunkConsumer) throws IOException {
        List<ChatMessage> copiedMessages = List.copyOf(messages);
        lastGeneratedMessages.set(copiedMessages);
        String answer = mockAnswer(lastUserContent(copiedMessages));
        for (int start = 0; start < answer.length(); start += STREAM_CHUNK_SIZE) {
            chunkConsumer.accept(answer.substring(start, Math.min(start + STREAM_CHUNK_SIZE, answer.length())));
        }
    }

    @Override
    public AiEvaluation evaluateAnswer(String question, String candidateAnswer, String userAnswer) {
        // 确定性评分规则：按回答长度分档，便于测试覆盖状态机全部分支（<4 / 4-6 / >=7）
        if (userAnswer == null || userAnswer.isBlank()) {
            return new AiEvaluation(0, "未作答");
        }
        int length = userAnswer.trim().length();
        if (length < 10) {
            return new AiEvaluation(3, "回答过于简短，需要展开说明");
        }
        if (length < 30) {
            return new AiEvaluation(5, "回答覆盖部分要点，但不够完整");
        }
        return new AiEvaluation(8, "回答覆盖主要要点，表达清晰");
    }

    @Override
    public AnswerEvaluation evaluateAnswerDetail(String question, String knowledgePoint, String candidateAnswer, String userAnswer) {
        return evaluateAnswerDetail(question, knowledgePoint, candidateAnswer, userAnswer, false);
    }

    @Override
    public AnswerEvaluation evaluateAnswerDetail(String question, String knowledgePoint, String candidateAnswer,
                                                 String userAnswer, boolean detailed) {
        // 确定性分档与 evaluateAnswer 一致，四维度同值（加权后 overall 不变），便于测试按回答长度控制状态机分支；
        // detailed=true 时补充 goodPoints/badPoints/improvedAnswer（训练模式详细反馈与深度训练）
        if (userAnswer == null || userAnswer.isBlank()) {
            return new AnswerEvaluation(0, 0, 0, 0, 0,
                    List.of(), List.of("未提供有效回答"), List.of(), "未作答",
                    detailed ? List.of() : null, detailed ? List.of("未提供有效回答") : null,
                    detailed ? "（参考回答）请围绕该知识点给出条理清晰的标准回答。" : null);
        }
        int length = userAnswer.trim().length();
        if (length < 10) {
            return new AnswerEvaluation(3, 3, 3, 3, 3,
                    List.of("核心原理与关键要点"), List.of("关键要点展开不足"), List.of(), "回答过于简短，需要展开说明",
                    detailed ? List.of() : null, detailed ? List.of("回答过于简短，关键要点展开不足") : null,
                    detailed ? "（参考回答）应先说明核心原理，再逐条展开关键要点并结合场景举例。" : null);
        }
        if (length < 30) {
            return new AnswerEvaluation(5, 5, 5, 5, 5,
                    List.of("核心原理与关键要点"), List.of("部分关键要点未覆盖"), List.of(), "回答覆盖部分要点，但不够完整",
                    detailed ? List.of("覆盖了核心原理") : null,
                    detailed ? List.of("部分关键要点未覆盖") : null,
                    detailed ? "（参考回答）在现有回答基础上补齐遗漏要点，并补充原理层面的分析。" : null);
        }
        return new AnswerEvaluation(8, 8, 8, 8, 8,
                List.of("核心原理与关键要点"), List.of(), List.of(), "回答覆盖主要要点，表达清晰",
                detailed ? List.of("覆盖主要要点，表达清晰") : null, detailed ? List.of() : null,
                detailed ? "（参考回答）回答已基本达标，可进一步补充边界情况与设计权衡。" : null);
    }

    @Override
    public String generateFollowUpQuestion(String prompt) {
        Matcher matcher = FOLLOW_UP_SOURCE_PATTERN.matcher(prompt);
        String source = matcher.find() ? matcher.group(1).trim() : "原问题";
        return "关于「" + source + "」，换个角度：你能结合实际场景举个例子说明吗？";
    }

    @Override
    public ReportSummary generateReportSummary(String prompt) {
        // 确定性摘要：从逐题评估记录中提取最高/最低分题，生成可断言的文本总结
        List<String> questions = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        Matcher matcher = REPORT_SCORE_PATTERN.matcher(prompt);
        while (matcher.find() && questions.size() < 20) {
            questions.add(matcher.group(1).trim());
            try {
                scores.add(Double.parseDouble(matcher.group(2)));
            } catch (NumberFormatException exception) {
                scores.add(0.0);
            }
        }
        if (questions.isEmpty()) {
            return new ReportSummary(List.of("完成了全部面试环节"), List.of(), List.of("建议持续系统化复习"));
        }
        int best = 0;
        int worst = 0;
        for (int index = 1; index < scores.size(); index++) {
            if (scores.get(index) > scores.get(best)) {
                best = index;
            }
            if (scores.get(index) < scores.get(worst)) {
                worst = index;
            }
        }
        return new ReportSummary(
                List.of("「" + questions.get(best) + "」回答较好（得分" + scores.get(best) + "），要点覆盖完整"),
                List.of("「" + questions.get(worst) + "」回答薄弱（得分" + scores.get(worst) + "），需重点复习"),
                List.of("针对薄弱知识点做专题练习，并结合实际场景举例说明"));
    }

    @Override
    public String parseResume(String rawText) {
        // 确定性解析：按“姓名/项目名称/技术栈”标记行提取，便于测试断言
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        Matcher nameMatcher = RESUME_NAME_PATTERN.matcher(rawText);
        String name = nameMatcher.find() ? nameMatcher.group(1).trim() : null;
        List<Map<String, Object>> projects = new ArrayList<>();
        Matcher projectMatcher = RESUME_PROJECT_PATTERN.matcher(rawText);
        Matcher techMatcher = RESUME_TECH_PATTERN.matcher(rawText);
        while (projectMatcher.find()) {
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("projectName", projectMatcher.group(1).trim());
            project.put("techStack", techMatcher.find() ? techMatcher.group(1).trim() : "");
            projects.add(project);
        }
        if (name == null && projects.isEmpty()) {
            return null;
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("name", name == null ? "未命名候选人" : name);
        parsed.put("projects", projects);
        try {
            return objectMapper.writeValueAsString(parsed);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    @Override
    public List<AiGeneratedQuestion> generateProjectQuestions(String prompt) {
        // 确定性生成：题面携带项目名，便于集成测试断言项目题基于简历内容
        Matcher matcher = PROJECT_NAME_PATTERN.matcher(prompt);
        if (!matcher.find()) {
            return List.of();
        }
        String projectName = matcher.group(1).trim();
        return List.of(
                new AiGeneratedQuestion(
                        "请介绍一下「" + projectName + "」的整体架构和设计思路",
                        "项目架构",
                        List.of("整体架构与模块划分", "核心技术选型及理由", "关键数据流转链路"),
                        "MEDIUM"),
                new AiGeneratedQuestion(
                        "在「" + projectName + "」中，你遇到的最大技术难点是什么，如何解决的？",
                        "技术难点",
                        List.of("难点背景与影响", "方案对比与权衡", "落地效果与验证"),
                        "HARD"),
                new AiGeneratedQuestion(
                        "如果重做「" + projectName + "」，你会在哪些方面做优化？",
                        "反思与成长",
                        List.of("架构层面的改进", "技术选型的反思", "可维护性提升"),
                        "EASY"));
    }

    @Override
    public AiGeneratedQuestion generateDeepQuestion(String prompt) {
        // 确定性生成：深挖题引用原项目问题，便于集成测试断言深挖基于项目回答
        Matcher matcher = DEEP_QUESTION_PATTERN.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        String sourceQuestion = matcher.group(1).trim();
        return new AiGeneratedQuestion(
                "深挖追问：关于「" + sourceQuestion + "」，请展开讲讲具体实现细节与设计权衡",
                "项目深挖",
                List.of("具体实现细节", "设计权衡的理由", "性能与边界情况处理"),
                "HARD");
    }

    private String mockAnswer(String userPrompt) {
        Matcher questionMatcher = QUESTION_PATTERN.matcher(userPrompt);
        String question = questionMatcher.find() ? questionMatcher.group(1).trim() : "未提供问题";
        if (userPrompt.contains("<task>interviewer</task>")) {
            return "【模拟面试官】接下来请回答：" + question;
        }
        if (userPrompt.contains("<task>followup</task>")) {
            return "【模拟追问】请换个角度继续阐述：" + question;
        }
        if (userPrompt.contains("<task>deep-training</task>")) {
            return "【深度训练】请回答：" + question;
        }
        if (userPrompt.contains("<task>followup-gen</task>")) {
            return generateFollowUpQuestion(userPrompt);
        }
        if (userPrompt.contains("<task>report-summary</task>")) {
            ReportSummary summary = generateReportSummary(userPrompt);
            return "亮点：" + String.join("；", summary.strengths())
                    + "\n薄弱点：" + String.join("；", summary.weaknesses())
                    + "\n建议：" + String.join("；", summary.suggestions());
        }

        Matcher refMatcher = REF_PATTERN.matcher(userPrompt);
        List<String> refIds = refMatcher.results()
                .map(match -> match.group(1))
                .limit(10)
                .toList();

        return refIds.isEmpty()
                ? "【模拟回答】该知识点暂未覆盖，请补充知识库后重试。"
                : "【模拟回答】针对问题「" + question + "」，已基于知识库条目（id: " + String.join(", ", refIds)
                        + "）组织答案。真实回答请配置 OFFERFORGE_AI_PROVIDER=openai-compatible 后由模型生成。";
    }

    private String lastUserContent(List<ChatMessage> messages) {
        return messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.USER)
                .reduce((ignored, latest) -> latest)
                .map(ChatMessage::content)
                .orElse("");
    }

    List<ChatMessage> lastGeneratedMessages() {
        return lastGeneratedMessages.get();
    }
}
