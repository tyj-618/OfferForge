package com.offerforge.resume;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.tool.ToolContext;
import com.offerforge.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * get_resume_section 工具与 ToolRegistry 单测：各 section 参数、projectIndex、权限上下文透传。
 */
class GetResumeSectionToolTests {

    private final ResumeService resumeService = mock(ResumeService.class);
    private final GetResumeSectionTool tool = new GetResumeSectionTool(resumeService);

    @Test
    void delegatesToResumeServiceWithContext() {
        when(resumeService.renderSection(eq(1L), eq(7L), eq("projects"), eq(0)))
                .thenReturn("[项目1] 项目名称：秒杀系统");

        String result = tool.execute(new ToolContext(1L, 7L), Map.of("section", "projects", "projectIndex", 0));

        assertThat(result).contains("秒杀系统");
        verify(resumeService).renderSection(1L, 7L, "projects", 0);
    }

    @Test
    void supportsAllSectionsAndOptionalProjectIndex() {
        when(resumeService.renderSection(eq(1L), isNull(), eq("all"), isNull())).thenReturn("完整简历");

        assertThat(tool.execute(ToolContext.ofUser(1L), Map.of("section", "all"))).isEqualTo("完整简历");
        verify(resumeService).renderSection(1L, null, "all", null);
    }

    @Test
    void sectionRequired() {
        assertThatThrownBy(() -> tool.execute(ToolContext.ofUser(1L), Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void projectIndexAcceptsNumberOrString() {
        when(resumeService.renderSection(eq(1L), isNull(), eq("projects"), eq(2))).thenReturn("text");

        tool.execute(ToolContext.ofUser(1L), Map.of("section", "projects", "projectIndex", "2"));

        verify(resumeService).renderSection(1L, null, "projects", 2);
    }

    @Test
    void invalidProjectIndexRejected() {
        assertThatThrownBy(() ->
                tool.execute(ToolContext.ofUser(1L), Map.of("section", "projects", "projectIndex", "abc")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void descriptionCoversAllSections() {
        assertThat(tool.name()).isEqualTo("get_resume_section");
        assertThat(tool.description())
                .contains("education").contains("skills").contains("projects")
                .contains("internships").contains("selfIntroduction").contains("all")
                .contains("projectIndex");
    }

    @Test
    void registryRoutesByNameAndRejectsUnknown() {
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertThat(registry.tools()).hasSize(1);
        assertThat(registry.get("get_resume_section")).isSameAs(tool);

        when(resumeService.renderSection(eq(1L), isNull(), eq("skills"), isNull())).thenReturn("Java, Redis");
        assertThat(registry.invoke("get_resume_section", ToolContext.ofUser(1L), Map.of("section", "skills")))
                .isEqualTo("Java, Redis");

        assertThatThrownBy(() -> registry.invoke("missing_tool", ToolContext.ofUser(1L), Map.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }
}
