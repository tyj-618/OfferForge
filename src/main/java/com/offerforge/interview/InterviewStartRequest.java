package com.offerforge.interview;

import java.util.List;

/**
 * start 请求体；position 为空时服务端使用缺省岗位方向，resumeId 可空（关联简历出项目题）。
 * mode：training（训练模式）/ practice（实战模式），空或非法值按 practice 处理。
 * categories：勾选的资料分组（可空）；非空时出题仅用这些分组。
 * includeAlgorithm：是否包含算法手写编程题（DEEP 阶段按难度掺入，默认否）。
 * style：助手语气风格（strict/friendly，缺省 friendly）。
 * model：付费模型选择（可空，空为系统默认模型；需充值余额支撑）。
 */
public record InterviewStartRequest(String position, Long resumeId, String mode, List<String> categories,
                                    Boolean includeAlgorithm, String style, String model) {
}
