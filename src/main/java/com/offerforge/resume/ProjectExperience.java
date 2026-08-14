package com.offerforge.resume;

/**
 * 项目经历结构（Resume.projects JSON 内的单个项目）。
 */
public record ProjectExperience(
        String projectName,
        String role,
        String duration,
        String description,
        String techStack,
        String highlights,
        String challenges
) {
}
