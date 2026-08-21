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
 * <p>无效回答（“不知道/不清楚”等短句）在调 LLM 前由服务端直接判低分，不消耗评分调用。</p>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final double FOLLOW_UP_THRESHOLD = 4.0;
    private static final double ADVANCE_THRESHOLD = 7.0;
    /** 无效回答检测：trim 后不超过该字数且命中关键词才判无效，避免误伤正常短回答 */
    private static final int INVALID_ANSWER_MAX_LENGTH = 20;
    private static final List<String> INVALID_ANSWER_MARKERS = List.of(
            "不知道", "不清楚", "不了解", "没了解过", "没听说过", "不会", "忘了");

    private final AiModelClient aiModelClient;

    public EvaluationService(AiModelClient aiModelClient) {
        this.aiModelClient = aiModelClient;
    }

    /**
     * 评估一次作答；knowledgePoint/candidateAnswer 可为 null（项目类问题无标准答案）。
     */
    public AnswerEvaluation evaluate(String question, String knowledgePoint, String candidateAnswer, String userAnswer) {
        return evaluate(question, knowledgePoint, candidateAnswer, userAnswer, false);
    }

    /**
     * 评估一次作答；detailed=true 时要求产出 goodPoints/badPoints/improvedAnswer（训练模式/深度训练）。
     */
    public AnswerEvaluation evaluate(String question, String knowledgePoint, String candidateAnswer,
                                     String userAnswer, boolean detailed) {
        if (isInvalidAnswer(userAnswer)) {
            // 服务端兜底：“不知道/不清楚”等无效回答直接固定低档，不进入 LLM，杜绝模型臆造正面评价
            log.info("interview evaluate invalid-answer shortcut question={}", question);
            return new AnswerEvaluation(1, 1, 1, 1, 1,
                    List.of(), List.of("未提供有效回答"), List.of(),
                    "该回答未提供实质内容（不知道/不清楚类回答）。建议先梳理该知识点的基本概念与核心原理，再结合实例重新作答。",
                    detailed ? List.of() : null,
                    detailed ? List.of("未提供有效回答，无实质内容") : null,
                    detailed ? "（参考回答）遇到不会的问题，可以先说明已知的相关背景，再诚实地指出不确定的部分，避免直接放弃作答。" : null);
        }
        AnswerEvaluation evaluation = aiModelClient.evaluateAnswerDetail(question, knowledgePoint, candidateAnswer, userAnswer, detailed);
        if (evaluation == null) {
            log.warn("interview evaluate fallback question={}", question);
            return new AnswerEvaluation(5, 5, 5, 5, 5, List.of(), List.of(), List.of(),
                    "评估服务异常，按中等处理", null, null, null);
        }
        return evaluation;
    }

    /**
     * 评估开场自我介绍（仅训练模式展示用，不入报告）：无知识点标准答案，
     * 按信息完整度/表达/岗位相关性评估；无效回答快捷键与知识题评估一致。
     */
    public AnswerEvaluation evaluateIntro(String intro, String position) {
        if (isInvalidAnswer(intro)) {
            log.info("interview evaluate-intro invalid-answer shortcut");
            return new AnswerEvaluation(1, 1, 1, 1, 1,
                    List.of(), List.of("未提供有效自我介绍"), List.of(),
                    "该自我介绍未提供实质内容。建议从教育/工作背景、项目经历与技术栈三方面重新组织。",
                    List.of(), List.of("未提供有效自我介绍，无实质内容"),
                    "（改进示范）可从教育/工作背景、主要项目经历与个人职责、熟悉的技术栈三方面简要介绍自己。");
        }
        AnswerEvaluation evaluation = aiModelClient.evaluateIntroDetail(intro, position);
        if (evaluation == null) {
            log.warn("interview evaluate-intro fallback");
            return new AnswerEvaluation(5, 5, 5, 5, 5, List.of(), List.of(), List.of(),
                    "评估服务异常，按中等处理", List.of(), List.of(), null);
        }
        return evaluation;
    }

    /**
     * 无效回答检测：trim 后 ≤ 20 字且命中“不知道/不会”等关键词。
     */
    static boolean isInvalidAnswer(String userAnswer) {
        if (userAnswer == null) {
            return false;
        }
        String trimmed = userAnswer.trim();
        if (trimmed.isEmpty() || trimmed.length() > INVALID_ANSWER_MAX_LENGTH) {
            return false;
        }
        return INVALID_ANSWER_MARKERS.stream().anyMatch(trimmed::contains);
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
