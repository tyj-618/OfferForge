package com.offerforge.ai;

import java.util.List;

/**
 * 回答质量结构化评估结果：四维度评分（0-10）+ 加权综合分 + 要点清单 + 点评。
 * 综合分权重：准确性 0.35 + 完整性 0.25 + 清晰度 0.20 + 深度 0.20，
 * 由服务端重算，不信任模型自报值。
 * <p>goodPoints/badPoints/improvedAnswer 仅训练模式详细评估时产出（可为 null，使用处判空）。</p>
 */
public record AnswerEvaluation(
        double accuracy,
        double completeness,
        double clarity,
        double depth,
        double overall,
        List<String> keyPoints,
        List<String> missedPoints,
        List<String> wrongPoints,
        String feedback,
        List<String> goodPoints,
        List<String> badPoints,
        String improvedAnswer
) {
}
