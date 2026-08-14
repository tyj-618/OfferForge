package com.offerforge.ai;

import java.util.List;

/**
 * 回答质量结构化评估结果：三维度评分（0-10）+ 加权综合分 + 遗漏/错误要点 + 一句话点评。
 * 综合分权重：准确性 0.4 + 完整性 0.35 + 清晰度 0.25，由服务端重算，不信任模型自报值。
 */
public record AnswerEvaluation(
        double accuracy,
        double completeness,
        double clarity,
        double overall,
        List<String> missedPoints,
        List<String> wrongPoints,
        String feedback
) {
}
