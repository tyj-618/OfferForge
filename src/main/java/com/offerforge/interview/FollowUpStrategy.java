package com.offerforge.interview;

import com.offerforge.ai.AiModelClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 追问策略：触发条件（评分 < 4 且追问次数未达上限）+ 追问题面生成。
 * 追问围绕同一知识点换角度、略降难度、可带提示，题面由 LLM 生成后仍经服务端话术包装发出。
 */
@Component
public class FollowUpStrategy {

    private final InterviewProperties properties;
    private final AiModelClient aiModelClient;

    public FollowUpStrategy(InterviewProperties properties, AiModelClient aiModelClient) {
        this.properties = properties;
        this.aiModelClient = aiModelClient;
    }

    /**
     * 是否触发追问：回答评分 < 4 且当前题追问次数 < 上限（默认 2 次）。
     */
    public boolean shouldFollowUp(double overall, int currentFollowUpCount) {
        return overall < 4 && currentFollowUpCount < properties.getMaxFollowUps();
    }

    public int maxFollowUps() {
        return properties.getMaxFollowUps();
    }

    /**
     * 生成追问题面：把候选人作答预览与评估发现的遗漏/错误要点传给模型，要求同知识点换角度、略降难度。
     */
    public String generateFollowUpQuestion(String originalQuestion, String candidateAnswer,
                                           List<String> missedPoints, List<String> wrongPoints) {
        String prompt = buildPrompt(originalQuestion, candidateAnswer, missedPoints, wrongPoints);
        String generated = aiModelClient.generateFollowUpQuestion(prompt);
        return generated == null || generated.isBlank()
                ? "关于「" + originalQuestion + "」，你能结合一个实际场景再具体说明一下吗？"
                : generated.trim();
    }

    String buildPrompt(String originalQuestion, String candidateAnswer, List<String> missedPoints, List<String> wrongPoints) {
        StringBuilder prompt = new StringBuilder("<task>followup-gen</task>\n")
                .append("候选人对问题「").append(originalQuestion).append("」的回答不够理想。\n");
        if (candidateAnswer != null && !candidateAnswer.isBlank()) {
            prompt.append("候选人回答预览：").append(candidateAnswer).append("\n");
        }
        prompt.append("遗漏的要点：").append(formatPoints(missedPoints)).append("\n")
                .append("错误的说法：").append(formatPoints(wrongPoints)).append("\n\n")
                .append("请生成一个追问，要求：\n")
                .append("1. 围绕同一知识点，但换一个新角度，可针对候选人回答中暴露的具体薄弱处\n")
                .append("2. 难度比原题略低\n")
                .append("3. 可以包含一个提示，引导候选人思考\n")
                .append("4. 若候选人回答含糊或缺少关键信息（具体职责、实现细节、数据量级、量化结果），追问应主动索取这些信息\n")
                .append("5. 若原题涉及项目经历，追问应深入项目实现：架构设计、个人职责、技术难点与解决方案\n")
                .append("6. 语气鼓励，不要打击信心\n")
                .append("7. 用中文提问\n")
                .append("只输出追问的问题本身，不要输出其他内容。");
        return prompt.toString();
    }

    private String formatPoints(List<String> points) {
        if (points == null || points.isEmpty()) {
            return "（无）";
        }
        return String.join("；", points);
    }
}
