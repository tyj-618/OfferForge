package com.offerforge.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.AiModelClient;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResumeService 单测：CRUD、纯文本解析回填、section 渲染（含 projectIndex）。
 */
class ResumeServiceTests {

    private ResumeRepository repository;
    private AiModelClient aiModelClient;
    private ResumeService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResumeRepository.class);
        aiModelClient = mock(AiModelClient.class);
        service = new ResumeService(repository, aiModelClient, new ObjectMapper());
        when(repository.save(any(Resume.class))).thenAnswer(invocation -> {
            Resume resume = invocation.getArgument(0);
            if (resume.getId() == null) {
                resume.setId(100L);
            }
            return resume;
        });
    }

    @Test
    void createResumeSerializesProjects() {
        ResumeRequest request = new ResumeRequest(null, "张三", "某大学 计算机", "Java, MySQL",
                List.of(new ProjectExperience("秒杀系统", "后端负责人", "2025.01-2025.06",
                        "高并发秒杀", "Spring Boot, Redis, MQ", "支撑 10w QPS", "超卖问题")),
                "某公司实习", "热爱后端开发", null);

        ResumeResponse response = service.save(1L, request);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.name()).isEqualTo("张三");
        assertThat(response.projects()).hasSize(1);
        assertThat(response.projects().get(0).projectName()).isEqualTo("秒杀系统");
        verify(repository).save(any(Resume.class));
    }

    @Test
    void emptyContentRejected() {
        ResumeRequest request = new ResumeRequest(null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.save(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void updateOthersResumeRejected() {
        when(repository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());
        ResumeRequest request = new ResumeRequest(9L, "张三", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.save(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void rawTextOnlyParsedByLlmAndBackfilled() {
        when(aiModelClient.parseResume(any())).thenReturn(
                "{\"name\":\"李四\",\"projects\":[{\"projectName\":\"商城系统\",\"techStack\":\"Java, Redis\"}]}");
        ResumeRequest request = new ResumeRequest(null, null, null, null, null, null, null,
                "姓名：李四\n项目名称：商城系统");

        ResumeResponse response = service.save(1L, request);

        assertThat(response.name()).isEqualTo("李四");
        assertThat(response.projects()).extracting(ProjectExperience::projectName).containsExactly("商城系统");
        assertThat(response.rawText()).contains("李四");
    }

    @Test
    void rawTextParseFailureRequiresStructuredInfo() {
        when(aiModelClient.parseResume(any())).thenReturn(null);
        ResumeRequest request = new ResumeRequest(null, null, null, null, null, null, null, "一段无法解析的文本");

        assertThatThrownBy(() -> service.save(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void listLatestAndDeleteRespectOwnership() {
        Resume resume = sampleResume(100L, 1L, "张三");
        when(repository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(resume));
        when(repository.findFirstByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(Optional.of(resume));
        when(repository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(resume));

        assertThat(service.list(1L)).extracting(ResumeSummary::id).containsExactly(100L);
        assertThat(service.latest(1L).name()).isEqualTo("张三");
        assertThat(service.getOwned(1L, 100L).name()).isEqualTo("张三");

        service.delete(1L, 100L);
        verify(repository).delete(resume);

        when(repository.findFirstByUserIdOrderByUpdatedAtDesc(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.latest(2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void renderSectionProducesPlainText() {
        Resume resume = sampleResume(100L, 1L, "张三");
        resume.setEducation("某大学 计算机本科");
        resume.setSkills("Java, MySQL");
        resume.setProjects("[{\"projectName\":\"秒杀系统\",\"role\":\"后端\",\"duration\":\"2025\","
                + "\"description\":\"高并发\",\"techStack\":\"Spring Boot, Redis\",\"highlights\":\"10w QPS\",\"challenges\":\"超卖\"}]");

        assertThat(service.renderSection(resume, "education", null)).contains("某大学");
        assertThat(service.renderSection(resume, "skills", null)).contains("Java");
        assertThat(service.renderSection(resume, "selfIntroduction", null)).contains("热爱技术");
        String projects = service.renderSection(resume, "projects", null);
        assertThat(projects).contains("[项目1] 项目名称：秒杀系统").contains("技术栈：Spring Boot, Redis");
        assertThat(service.renderSection(resume, "projects", 0)).contains("秒杀系统");
        assertThat(service.renderSection(resume, "all", null))
                .contains("姓名：张三").contains("【教育经历】").contains("秒杀系统");
    }

    @Test
    void renderSectionRejectsInvalidParams() {
        Resume resume = sampleResume(100L, 1L, "张三");
        resume.setProjects("[{\"projectName\":\"秒杀系统\"}]");

        assertThatThrownBy(() -> service.renderSection(resume, "unknown", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
        assertThatThrownBy(() -> service.renderSection(resume, "projects", 5))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void getProjectsReturnsStructuredList() {
        Resume resume = sampleResume(100L, 1L, "张三");
        resume.setProjects("[{\"projectName\":\"秒杀系统\",\"techStack\":\"Java, Redis\"},{\"projectName\":\"博客\"}]");
        when(repository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(resume));

        List<ProjectExperience> projects = service.getProjects(1L, 100L);

        assertThat(projects).extracting(ProjectExperience::projectName).containsExactly("秒杀系统", "博客");
    }

    private Resume sampleResume(long id, long userId, String name) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(userId);
        resume.setName(name);
        resume.setSelfIntroduction("热爱技术，善于学习");
        return resume;
    }
}
