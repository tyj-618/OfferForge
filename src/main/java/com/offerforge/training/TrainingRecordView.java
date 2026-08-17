package com.offerforge.training;

import java.time.Instant;

/** 训练历史列表条目（GET /api/training/records） */
public record TrainingRecordView(
        Long id,
        String category,
        int askedCount,
        double averageScore,
        String maxDifficulty,
        Instant finishedAt
) {
    public static TrainingRecordView from(TrainingRecord record) {
        return new TrainingRecordView(record.getId(), record.getCategory(), record.getAskedCount(),
                record.getAverageScore(), record.getMaxDifficulty(), record.getFinishedAt());
    }
}
