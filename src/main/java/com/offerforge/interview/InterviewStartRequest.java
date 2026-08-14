package com.offerforge.interview;

/**
 * start 请求体；position 为空时服务端使用缺省岗位方向，resumeId 可空（关联简历出项目题）。
 */
public record InterviewStartRequest(String position, Long resumeId) {
}
