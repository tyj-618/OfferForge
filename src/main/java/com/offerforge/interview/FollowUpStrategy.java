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
     * 生成追问题面：把评估发现的遗漏/错误要点传给模型，要求同知识点换角度、略降难度。
     */
    public String generateFollowUpQuestion(String originalQuestion, List<String> missedPoints, List<String> wrongPoints) {
        String prompt = buildPrompt(originalQuestion, missedPoints, wrongPoints);
        String generated = aiModelClient.generateFollowUpQuestion(prompt);
        return generated == null || generated.isBlank()
                ? "关于「" + originalQuestion + "」，你能结合一个实际场景再具体说明一下吗？"
                : generated.trim();
    }

    String buildPrompt(String originalQuestion, List<String> missedPoints, List<String> wrongPoints) {
        return "<task>followup-gen</task>\n"
                + "候选人对问题「" + originalQuestion + "」的回答不够理想。\n"
                + "遗漏的要点：" + formatPoints(missedPoints) + "\n"
                + "错误的说法：" + formatPoints(wrongPoints) + "\n\n"
                + "请生成一个追问，要求：\n"
                + "1. 围绕同一知识点，但换一个新角度\n"
                + "2. 难度比原题略低\n"
                + "3. 可以包含一个提示，引导候选人思考\n"
                + "4. 语气鼓励，不要打击信心\n"
                + "5. 用中文提问\n"
                + "只输出追问的问题本身，不要输出其他内容。";
    }

    private String formatPoints(List<String> points) {
        if (points == null || points.isEmpty()) {
            return "（无）";
        }
        return String.join("；", points);
    }
}
