package com.offerforge.resume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.AiModelClient;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 简历管理：多份简历 CRUD、按 section 渲染纯文本（供 Function Calling）、纯文本 LLM 解析。
 * <p>敏感信息策略：日志只打印 resumeId 等标识，绝不打印简历正文。</p>
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final Set<String> SECTIONS =
            Set.of("education", "skills", "projects", "internships", "selfintroduction", "all");
    /** 原始简历文本长度上限，防止超大打爆存储与 LLM 上下文 */
    private static final int MAX_RAW_TEXT_LENGTH = 20000;

    private final ResumeRepository resumeRepository;
    private final AiModelClient aiModelClient;
    private final ObjectMapper objectMapper;

    public ResumeService(ResumeRepository resumeRepository, AiModelClient aiModelClient, ObjectMapper objectMapper) {
        this.resumeRepository = resumeRepository;
        this.aiModelClient = aiModelClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建或更新简历：id 为空创建新份，非空更新本人名下对应简历。
     * 结构化字段全空且带 rawText 时先调 LLM 解析回填，解析失败仅保存原文。
     */
    @Transactional
    public ResumeResponse save(Long userId, ResumeRequest request) {
        if (request == null || !request.hasContent()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "简历内容不能为空");
        }
        if (request.rawText() != null && request.rawText().length() > MAX_RAW_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "简历原文过长，请精简后重试");
        }
        ResumeRequest effective = request;
        if (request.structuredEmpty() && request.rawText() != null && !request.rawText().isBlank()) {
            effective = request.mergeParsed(parseRawText(request.rawText()));
        }
        if (effective.structuredEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "简历解析失败，请补充姓名等结构化信息");
        }
        Resume resume;
        if (request.id() == null) {
            resume = new Resume();
            resume.setUserId(userId);
        } else {
            resume = getOwnedEntity(userId, request.id());
        }
        applyRequest(resume, effective);
        Resume saved = resumeRepository.save(resume);
        log.info("resume saved resumeId={} userId={} name={}", saved.getId(), userId, saved.getName());
        return toResponse(saved);
    }

    /**
     * 纯文本解析（不落库）：供前端粘贴预览；解析失败返回仅含 rawText 的结果。
     */
    public ResumeRequest parseRawText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "简历原文不能为空");
        }
        try {
            String json = aiModelClient.parseResume(rawText);
            if (json != null && !json.isBlank()) {
                ResumeRequest parsed = objectMapper.readValue(json, ResumeRequest.class);
                if (parsed.hasContent()) {
                    return parsed;
                }
            }
        } catch (JsonProcessingException exception) {
            log.warn("resume parse result invalid, fallback to raw text only");
        } catch (RuntimeException exception) {
            log.warn("resume parse unavailable, fallback to raw text only: {}", exception.getMessage());
        }
        return new ResumeRequest(null, null, null, null, null, null, null, rawText);
    }

    /** 当前用户全部简历（按更新时间倒序） */
    public List<ResumeSummary> list(Long userId) {
        return resumeRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(resume -> new ResumeSummary(resume.getId(), resume.getName(), resume.getUpdatedAt()))
                .toList();
    }

    /** 最近更新的一份简历；不存在抛 NOT_FOUND */
    public ResumeResponse latest(Long userId) {
        return resumeRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
    }

    /** 按 id 获取本人简历；不存在或非本人抛 NOT_FOUND */
    public ResumeResponse getOwned(Long userId, Long resumeId) {
        return toResponse(getOwnedEntity(userId, resumeId));
    }

    @Transactional
    public void delete(Long userId, Long resumeId) {
        Resume resume = getOwnedEntity(userId, resumeId);
        resumeRepository.delete(resume);
        log.info("resume deleted resumeId={} userId={}", resumeId, userId);
    }

    /**
     * 项目经历结构化列表（供项目题生成选人/选项目）；简历不存在或非本人抛 NOT_FOUND。
     */
    public List<ProjectExperience> getProjects(Long userId, Long resumeId) {
        return parseProjects(getOwnedEntity(userId, resumeId).getProjects());
    }

    /**
     * 渲染简历指定部分为纯文本（供 get_resume_section 工具）。
     *
     * @param resumeId     指定简历；为 null 时取最近更新的
     * @param section      education/skills/projects/internships/selfIntroduction/all
     * @param projectIndex section=projects 时可选，指定第几个项目（从 0 开始）
     */
    public String renderSection(Long userId, Long resumeId, String section, Integer projectIndex) {
        Resume resume = resumeId == null
                ? resumeRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"))
                : getOwnedEntity(userId, resumeId);
        return renderSection(resume, section, projectIndex);
    }

    String renderSection(Resume resume, String section, Integer projectIndex) {
        if (section == null || !SECTIONS.contains(section.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "section 仅支持 education/skills/projects/internships/selfIntroduction/all");
        }
        return switch (section.toLowerCase(Locale.ROOT)) {
            case "education" -> orPlaceholder(resume.getEducation(), "暂无教育经历");
            case "skills" -> orPlaceholder(resume.getSkills(), "暂无技能清单");
            case "projects" -> renderProjects(resume, projectIndex);
            case "internships" -> orPlaceholder(resume.getInternships(), "暂无实习经历");
            case "selfintroduction" -> orPlaceholder(resume.getSelfIntroduction(), "暂无自我介绍");
            default -> renderAll(resume);
        };
    }

    private String renderProjects(Resume resume, Integer projectIndex) {
        List<ProjectExperience> projects = parseProjects(resume.getProjects());
        if (projects.isEmpty()) {
            return "暂无项目经历";
        }
        if (projectIndex != null) {
            if (projectIndex < 0 || projectIndex >= projects.size()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "projectIndex 超出范围");
            }
            return formatProject(projectIndex, projects.get(projectIndex));
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < projects.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(formatProject(index, projects.get(index)));
        }
        return builder.toString();
    }

    private String renderAll(Resume resume) {
        List<ProjectExperience> projects = parseProjects(resume.getProjects());
        return "姓名：" + resume.getName() + "\n"
                + "【教育经历】\n" + orPlaceholder(resume.getEducation(), "暂无") + "\n"
                + "【技能清单】\n" + orPlaceholder(resume.getSkills(), "暂无") + "\n"
                + "【项目经历】\n" + (projects.isEmpty() ? "暂无" : String.join("\n",
                        java.util.stream.IntStream.range(0, projects.size())
                                .mapToObj(index -> formatProject(index, projects.get(index)))
                                .toList())) + "\n"
                + "【实习经历】\n" + orPlaceholder(resume.getInternships(), "暂无") + "\n"
                + "【自我介绍】\n" + orPlaceholder(resume.getSelfIntroduction(), "暂无");
    }

    private String formatProject(int index, ProjectExperience project) {
        return "[项目%d] 项目名称：%s\n角色：%s\n时间段：%s\n项目描述：%s\n技术栈：%s\n项目亮点：%s\n遇到的挑战：%s".formatted(
                index + 1,
                orPlaceholder(project.projectName(), "未命名项目"),
                orPlaceholder(project.role(), "未说明"),
                orPlaceholder(project.duration(), "未说明"),
                orPlaceholder(project.description(), "未说明"),
                orPlaceholder(project.techStack(), "未说明"),
                orPlaceholder(project.highlights(), "未说明"),
                orPlaceholder(project.challenges(), "未说明"));
    }

    private List<ProjectExperience> parseProjects(String projectsJson) {
        if (projectsJson == null || projectsJson.isBlank()) {
            return List.of();
        }
        try {
            List<ProjectExperience> projects =
                    objectMapper.readValue(projectsJson, new TypeReference<>() {
                    });
            return projects == null ? List.of() : projects;
        } catch (JsonProcessingException exception) {
            log.warn("resume projects json invalid, treat as empty");
            return List.of();
        }
    }

    private Resume getOwnedEntity(Long userId, Long resumeId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
    }

    private void applyRequest(Resume resume, ResumeRequest request) {
        String name = request.name() == null || request.name().isBlank() ? "未命名候选人" : request.name().trim();
        resume.setName(name);
        resume.setEducation(request.education());
        resume.setSkills(request.skills());
        resume.setProjects(serializeProjects(request.projects()));
        resume.setInternships(request.internships());
        resume.setSelfIntroduction(request.selfIntroduction());
        resume.setRawText(request.rawText());
    }

    private String serializeProjects(List<ProjectExperience> projects) {
        if (projects == null || projects.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(projects);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "项目经历格式非法");
        }
    }

    private ResumeResponse toResponse(Resume resume) {
        return new ResumeResponse(resume.getId(), resume.getName(), resume.getEducation(), resume.getSkills(),
                parseProjects(resume.getProjects()), resume.getInternships(), resume.getSelfIntroduction(),
                resume.getRawText(), resume.getCreatedAt(), resume.getUpdatedAt());
    }

    private String orPlaceholder(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
    }
}
