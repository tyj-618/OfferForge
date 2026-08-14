package com.offerforge.report;

/**
 * 推荐复习材料：针对薄弱知识点从知识库检索的练习方向。
 */
public record RecommendedMaterial(
        String topic,
        String reason,
        String suggestedQuestion
) {
}
