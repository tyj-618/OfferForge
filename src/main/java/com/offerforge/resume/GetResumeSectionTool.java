package com.offerforge.resume;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.tool.AgentTool;
import com.offerforge.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Function Calling 工具：获取候选人简历的指定部分（纯文本，方便 LLM 理解）。
 * 权限由 ToolContext 中服务端注入的 userId 保证——只能查自己的简历。
 */
@Component
public class GetResumeSectionTool implements AgentTool {

    public static final String TOOL_NAME = "get_resume_section";

    private final ResumeService resumeService;

    public GetResumeSectionTool(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return """
                获取候选人简历的指定部分。
                参数 section 可选值：
                - education: 教育背景
                - skills: 技能清单
                - projects: 项目经历
                - internships: 实习经历
                - selfIntroduction: 自我介绍
                - all: 完整简历
                当 section 为 projects 时，可通过 projectIndex 指定具体某个项目。""";
    }

    @Override
    public String execute(ToolContext context, Map<String, Object> arguments) {
        Object sectionValue = arguments.get("section");
        if (sectionValue == null || sectionValue.toString().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "section 参数必填");
        }
        Integer projectIndex = parseProjectIndex(arguments.get("projectIndex"));
        return resumeService.renderSection(context.userId(), context.resumeId(),
                sectionValue.toString().trim(), projectIndex);
    }

    private Integer parseProjectIndex(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "projectIndex 必须为整数");
        }
    }
}
