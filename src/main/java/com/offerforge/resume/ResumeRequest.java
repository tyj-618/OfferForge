package com.offerforge.resume;

import java.util.List;

/**
 * 简历创建/更新请求：id 为空创建，非空更新。
 * 结构化字段为空且 rawText 非空时，服务端调 LLM 解析原文回填结构化字段。
 */
public record ResumeRequest(
        Long id,
        String name,
        String education,
        String skills,
        List<ProjectExperience> projects,
        String internships,
        String selfIntroduction,
        String rawText
) {
    /** 是否包含任何有效内容 */
    public boolean hasContent() {
        return notBlank(name) || notBlank(education) || notBlank(skills)
                || (projects != null && !projects.isEmpty())
                || notBlank(internships) || notBlank(selfIntroduction) || notBlank(rawText);
    }

    /** 结构化字段是否全空（触发纯文本解析的前提） */
    public boolean structuredEmpty() {
        return !notBlank(name) && !notBlank(education) && !notBlank(skills)
                && (projects == null || projects.isEmpty())
                && !notBlank(internships) && !notBlank(selfIntroduction);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 合并 LLM 解析结果：仅回填原本为空的字段 */
    public ResumeRequest mergeParsed(ResumeRequest parsed) {
        return new ResumeRequest(
                id,
                notBlank(name) ? name : parsed.name(),
                notBlank(education) ? education : parsed.education(),
                notBlank(skills) ? skills : parsed.skills(),
                projects != null && !projects.isEmpty() ? projects : parsed.projects(),
                notBlank(internships) ? internships : parsed.internships(),
                notBlank(selfIntroduction) ? selfIntroduction : parsed.selfIntroduction(),
                rawText);
    }
}
