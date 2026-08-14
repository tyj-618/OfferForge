package com.offerforge.resume;

import java.time.LocalDateTime;

/**
 * 简历摘要（列表/面试前选择用，不含正文）。
 */
public record ResumeSummary(Long id, String name, LocalDateTime updatedAt) {
}
