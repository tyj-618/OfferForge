package com.offerforge.tool;

import java.util.Map;

/**
 * Function Calling 工具抽象：Agent 指令经 ToolRegistry 路由到具体工具执行。
 * 工具只能访问 ToolContext 携带的当前用户数据（权限由服务端注入，不信任入参）。
 */
public interface AgentTool {

    /** 工具名（供 LLM 调用），如 get_resume_section */
    String name();

    /** 工具描述（给 LLM 看的用途与参数说明） */
    String description();

    /**
     * 执行工具并返回纯文本结果（方便 LLM 理解）。
     *
     * @param context   服务端注入的执行上下文（当前用户等），不可由调用方伪造
     * @param arguments LLM 传入的参数键值对
     */
    String execute(ToolContext context, Map<String, Object> arguments);
}
