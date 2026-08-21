package com.offerforge.report;

import java.time.Instant;

/**
 * 进步曲线数据点：按面试开始时间正序返回，便于前端绘制趋势折线；
 * mode 区分训练/实战，前端按模式分线绘制。
 */
public record InterviewProgressPoint(
        String interviewId,
        Instant interviewTime,
        double overallScore,
        String mode
) {
}
