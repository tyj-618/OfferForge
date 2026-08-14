package com.offerforge.resume;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历详情视图：projects 已从 JSON 反序列化为结构化列表。
 */
public record ResumeResponse(
        Long id,
        String name,
        String education,
        String skills,
        List<ProjectExperience> projects,
        String internships,
        String selfIntroduction,
        String rawText,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
