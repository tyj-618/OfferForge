package com.offerforge.interview;

/**
 * start 请求体；position 为空时服务端使用缺省岗位方向，resumeId 可空（关联简历出项目题）。
 * mode：training（训练模式）/ practice（实战模式），空或非法值按 practice 处理。
 */
public record InterviewStartRequest(String position, Long resumeId, String mode) {
}
