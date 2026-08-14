package com.offerforge.ai;

import java.util.List;

/**
 * LLM 生成的面试题（项目题/深挖题）：题面 + 考察知识点 + 参考答案要点 + 难度（EASY/MEDIUM/HARD）。
 */
public record AiGeneratedQuestion(
        String question,
        String knowledgePoint,
        List<String> referenceAnswer,
        String difficulty
) {
}
