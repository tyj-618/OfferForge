package com.offerforge.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 启动后异步全量重建知识向量索引：幂等 upsert，
 * 补齐历史上传时索引不可用而缺失的私有资料；未启用搜索时内部直接跳过。
 */
@Component
public class KnowledgeIndexReindexRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexReindexRunner.class);

    private final KnowledgeService knowledgeService;

    public KnowledgeIndexReindexRunner(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            try {
                knowledgeService.reindexAll();
            } catch (Exception exception) {
                // 索引重建失败不影响应用可用性，检索会降级到关键词路径
                log.warn("knowledge reindex on startup failed: {}", exception.getMessage());
            }
        });
    }
}
