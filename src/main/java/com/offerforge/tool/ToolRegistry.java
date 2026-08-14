package com.offerforge.tool;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function Calling 工具注册表：Spring 自动收集所有 AgentTool 实现并按名称索引。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        toolList.forEach(tool -> tools.put(tool.name(), tool));
    }

    /**
     * 按名称执行工具；工具不存在抛 NOT_FOUND，参数问题由各工具自行校验。
     */
    public String invoke(String toolName, ToolContext context, Map<String, Object> arguments) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工具不存在: " + toolName);
        }
        return tool.execute(context, arguments == null ? Map.of() : arguments);
    }

    public AgentTool get(String toolName) {
        return tools.get(toolName);
    }

    public Collection<AgentTool> tools() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
