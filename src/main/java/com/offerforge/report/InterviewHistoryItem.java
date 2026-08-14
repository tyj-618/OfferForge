package com.offerforge.report;

import java.time.Instant;

/**
 * 历史面试列表条目：仅展示概要字段，完整报告通过 /api/report/{interviewId} 获取。
 */
public record InterviewHistoryItem(
        String interviewId,
        String position,
        Instant interviewTime,
        double overallScore,
        String status
) {
}
