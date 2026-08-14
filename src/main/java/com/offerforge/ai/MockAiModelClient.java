package com.offerforge.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "offerforge.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAiModelClient implements AiModelClient {

    private static final Pattern QUESTION_PATTERN = Pattern.compile("<question>\\s*(.*?)\\s*</question>", Pattern.DOTALL);
    private static final Pattern FOLLOW_UP_SOURCE_PATTERN = Pattern.compile("对问题「(.*?)」的回答不够理想", Pattern.DOTALL);
    private static final Pattern REF_PATTERN = Pattern.compile("\\[ref: (\\d+)]");
    private static final int STREAM_CHUNK_SIZE = 8;

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
    public AnswerEvaluation evaluateAnswerDetail(String question, String candidateAnswer, String userAnswer) {
        // 确定性分档与 evaluateAnswer 一致，三维度同值，便于测试按回答长度控制状态机分支
        if (userAnswer == null || userAnswer.isBlank()) {
            return new AnswerEvaluation(0, 0, 0, 0,
                    List.of("未提供有效回答"), List.of(), "未作答");
        }
        int length = userAnswer.trim().length();
        if (length < 10) {
            return new AnswerEvaluation(3, 2, 3, 3,
                    List.of("关键要点展开不足"), List.of(), "回答过于简短，需要展开说明");
        }
        if (length < 30) {
            return new AnswerEvaluation(5, 4, 5, 5,
                    List.of("部分关键要点未覆盖"), List.of(), "回答覆盖部分要点，但不够完整");
        }
        return new AnswerEvaluation(8, 8, 8, 8,
                List.of(), List.of(), "回答覆盖主要要点，表达清晰");
    }

    @Override
    public String generateFollowUpQuestion(String prompt) {
        Matcher matcher = FOLLOW_UP_SOURCE_PATTERN.matcher(prompt);
        String source = matcher.find() ? matcher.group(1).trim() : "原问题";
        return "关于「" + source + "」，换个角度：你能结合实际场景举个例子说明吗？";
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
        if (userPrompt.contains("<task>followup-gen</task>")) {
            return generateFollowUpQuestion(userPrompt);
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
