package com.offerforge.interview;

/**
 * start 请求体；position 为空时服务端使用缺省岗位方向。
 */
public record InterviewStartRequest(String position) {
}
