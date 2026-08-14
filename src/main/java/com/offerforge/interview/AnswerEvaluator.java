package com.offerforge.interview;

import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AnswerEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 回答质量评估：每次作答后调用 LLM 快速评估。
 * 评估 Prompt 与 JSON 解析由模型客户端实现；本组件负责兜底校验，
 * 保证返回值合法（分值 0-10、列表非 null），模型故障时返回中间档不阻断面试。
 */
@Component
public class AnswerEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluator.class);
    private static final double FOLLOW_UP_THRESHOLD = 4.0;
    private static final double ADVANCE_THRESHOLD = 7.0;

    private final AiModelClient aiModelClient;

    public AnswerEvaluator(AiModelClient aiModelClient) {
        this.aiModelClient = aiModelClient;
    }

    /**
     * 评估一次作答；candidateAnswer 为服务端参考答案（项目类问题可为 null）。
     */
    public AnswerEvaluation evaluate(String question, String candidateAnswer, String userAnswer) {
        AnswerEvaluation evaluation = aiModelClient.evaluateAnswerDetail(question, candidateAnswer, userAnswer);
        if (evaluation == null) {
            log.warn("interview evaluate fallback question={}", question);
            return new AnswerEvaluation(5, 5, 5, 5, java.util.List.of(), java.util.List.of(), "评估服务异常，按中等处理");
        }
        return evaluation;
    }

    /**
     * 回答差：触发追问。
     */
    public boolean isPoor(double overall) {
        return overall < FOLLOW_UP_THRESHOLD;
    }

    /**
     * 回答好：推进阶段或提升难度。
     */
    public boolean isStrong(double overall) {
        return overall >= ADVANCE_THRESHOLD;
    }
}
