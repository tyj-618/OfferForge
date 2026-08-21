package com.offerforge.training;

import java.time.Instant;
import java.util.List;

/**
 * 训练报告详情（GET /api/training/records/{id}/report）：
 * 概要统计 + 逐题明细（题面/回答/得分/导师点评/详细评估）。
 * <p>旧归档记录无明细时 details 为空列表，前端降级为仅展示概要。</p>
 */
public record TrainingReportView(
        Long id,
        String category,
        Instant startTime,
        Instant finishedAt,
        long durationMinutes,
        int askedCount,
        double averageScore,
        String maxDifficulty,
        String rating,
        List<TrainingQuestionRecord> details
) {
}
