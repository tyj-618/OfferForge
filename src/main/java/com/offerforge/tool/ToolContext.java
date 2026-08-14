package com.offerforge.tool;

/**
 * 工具执行上下文：由服务端在调用链中注入，保证工具只能触达当前用户自己的数据。
 *
 * @param userId   当前登录用户 id（必填）
 * @param resumeId 关联简历 id（可空；为空时工具自行取用户最近更新的简历）
 */
public record ToolContext(Long userId, Long resumeId) {

    public static ToolContext ofUser(Long userId) {
        return new ToolContext(userId, null);
    }
}
