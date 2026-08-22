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
    /** 固定模拟用量：计费链路集成测试凭此确定性断言扣费金额（真实客户端上报模型实际 usage） */
    private static final int MOCK_INPUT_TOKENS = 100;
    private static final int MOCK_OUTPUT_TOKENS = 50;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<List<ChatMessage>> lastGeneratedMessages = new AtomicReference<>(List.of());

    @Override
    public AiTextResult generateText(List<ChatMessage> messages) {
        List<ChatMessage> copiedMessages = List.copyOf(messages);
        lastGeneratedMessages.set(copiedMessages);
        String answer = mockAnswer(lastUserContent(copiedMessages));
        LlmCallContext.recordUsage(MOCK_INPUT_TOKENS, MOCK_OUTPUT_TOKENS);
        return new AiTextResult(answer, UUID.randomUUID().toString(), MOCK_INPUT_TOKENS, MOCK_OUTPUT_TOKENS);
    }

    @Override
    public void generateStream(List<ChatMessage> messages, AiStreamChunkConsumer chunkConsumer) throws IOException {
        List<ChatMessage> copiedMessages = List.copyOf(messages);
        lastGeneratedMessages.set(copiedMessages);
        String answer = mockAnswer(lastUserContent(copiedMessages));
        for (int start = 0; start < answer.length(); start += STREAM_CHUNK_SIZE) {
            chunkConsumer.accept(answer.substring(start, Math.min(start + STREAM_CHUNK_SIZE, answer.length())));
        }
        LlmCallContext.recordUsage(MOCK_INPUT_TOKENS, MOCK_OUTPUT_TOKENS);
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
    public AnswerEvaluation evaluateIntroDetail(String intro, String position) {
        // 确定性分档与知识题评估一致（按长度），便于测试控制分支；点评文案面向自我介绍场景（信息完整度/表达）
        if (intro == null || intro.isBlank()) {
            return new AnswerEvaluation(0, 0, 0, 0, 0,
                    List.of(), List.of("未提供自我介绍"), List.of(), "未作答",
                    List.of(), List.of("未提供自我介绍"),
                    "（改进示范）可从教育/工作背景、主要项目经历与技术栈三方面简要介绍自己。");
        }
        int length = intro.trim().length();
        if (length < 10) {
            return new AnswerEvaluation(3, 3, 3, 3, 3,
                    List.of("教育或工作背景", "项目经历与技术栈"),
                    List.of("自我介绍过于简短，缺少项目经历与技术栈信息"), List.of(),
                    "自我介绍过于简短，建议补充项目经历与技术栈",
                    List.of(), List.of("信息太少，建议补充项目经历、个人职责与技术栈"),
                    "（改进示范）先讲基本背景，再介绍主要项目经历与个人职责，最后说明熟悉的技术栈与求职方向。");
        }
        if (length < 30) {
            return new AnswerEvaluation(5, 5, 5, 5, 5,
                    List.of("教育或工作背景", "项目经历与技术栈"),
                    List.of("项目职责与技术细节展开不足"), List.of(),
                    "自我介绍覆盖部分信息，但项目与技术细节不够充分",
                    List.of("提供了基本背景信息"), List.of("项目职责与技术细节展开不足"),
                    "（改进示范）在现有基础上补充项目中的个人职责、技术难点与量化成果。");
        }
        return new AnswerEvaluation(8, 8, 8, 8, 8,
                List.of("教育或工作背景", "项目经历与技术栈"), List.of(), List.of(),
                "自我介绍信息充分，背景、项目与技术栈表达清晰",
                List.of("背景、项目经历与技术栈信息充分，表达清晰"), List.of(),
                "（改进示范）自我介绍已基本完善，可进一步补充项目中的量化成果与设计亮点。");
    }

    @Override
    public String generateFollowUpQuestion(String prompt) {
        Matcher matcher = FOLLOW_UP_SOURCE_PATTERN.matcher(prompt);
        String source = matcher.find() ? matcher.group(1).trim() : "原问题";
        return "关于「" + source + "」，换个角度：你能结合实际场景举个例子说明吗？";
    }

    @Override
    public String generateIntroFollowUp(String prompt) {
        // 确定性约定：自我介绍含「信息不全」标记时返回补充提问，其余视为信息充分直接推进；
        // 便于集成测试断言开场主动追问链路，不影响既有测试的默认推进行为
        if (prompt.contains("自我介绍：信息不全")) {
            return "你的自我介绍信息还不够，能补充一下你参与过的项目和熟悉的技术栈吗？";
        }
        return null;
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
        if (userPrompt.contains("<task>intro-summary</task>")) {
            // 开场背景提取 mock：携带「项目名称」标记行，便于 PROJECT 阶段背景出题的确定性断言
            return "候选人自述背景：\n项目名称：面试对话项目\n技术栈：Java\n";
        }
        if (userPrompt.contains("<task>mentor-feedback</task>")) {
            // 导师反馈 mock：不重复题面、不携带分数，便于断言独立气泡与顺序
            return "【导师反馈】针对你刚才的回答，我已给出人性化点评，继续保持。";
        }
        if (userPrompt.contains("<task>interviewer</task>")) {
            return "【模拟面试官】接下来请回答：" + question;
        }
        if (userPrompt.contains("<task>followup</task>")) {
            return "【模拟追问】请换个角度继续阐述：" + question;
        }
        if (userPrompt.contains("<task>deep-training</task>")) {
            return "【深度训练】请回答：" + question;
        }
        if (userPrompt.contains("<task>training</task>")) {
            return "【专项训练】请回答：" + question;
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
