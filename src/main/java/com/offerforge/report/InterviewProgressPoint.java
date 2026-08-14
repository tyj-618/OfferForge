package com.offerforge.report;

import java.time.Instant;

/**
 * 进步曲线数据点：按面试开始时间正序返回，便于前端绘制趋势折线。
 */
public record InterviewProgressPoint(
        String interviewId,
        Instant interviewTime,
        double overallScore
) {
}
