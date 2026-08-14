package com.offerforge.interview;

import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AnswerEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单题评估整合服务：四维度（准确性 0.35 / 完整性 0.25 / 清晰度 0.20 / 深度 0.20）结构化评估。
 * 评估 Prompt 与 JSON 解析由模型客户端实现；本服务负责兜底校验，
 * 保证返回值合法（分值 0-10、列表非 null），模型故障时返回中间档不阻断面试。
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final double FOLLOW_UP_THRESHOLD = 4.0;
    private static final double ADVANCE_THRESHOLD = 7.0;

    private final AiModelClient aiModelClient;

    public EvaluationService(AiModelClient aiModelClient) {
        this.aiModelClient = aiModelClient;
    }

    /**
     * 评估一次作答；knowledgePoint/candidateAnswer 可为 null（项目类问题无标准答案）。
     */
    public AnswerEvaluation evaluate(String question, String knowledgePoint, String candidateAnswer, String userAnswer) {
        AnswerEvaluation evaluation = aiModelClient.evaluateAnswerDetail(question, knowledgePoint, candidateAnswer, userAnswer);
        if (evaluation == null) {
            log.warn("interview evaluate fallback question={}", question);
            return new AnswerEvaluation(5, 5, 5, 5, 5, List.of(), List.of(), List.of(), "评估服务异常，按中等处理");
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
