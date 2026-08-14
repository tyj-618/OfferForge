package com.offerforge.ai;

import java.util.List;

/**
 * 面试报告文本摘要：LLM 只产出文字总结（亮点/薄弱点/改进建议），
 * 评分与统计由服务端计算，保证报告数值可信。
 */
public record ReportSummary(
        List<String> strengths,
        List<String> weaknesses,
        List<String> suggestions
) {
}
