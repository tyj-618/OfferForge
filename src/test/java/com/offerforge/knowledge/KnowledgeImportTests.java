package com.offerforge.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class KnowledgeImportTests {

    /** 内置官方题库总题数：4 个方向资源文件合计（64 Java + 52 前端 + 56 Go/测试/运维 + 46 AI） */
    private static final int BUILTIN_TOTAL = 218;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeRepository knowledgeRepository;

    @BeforeEach
    void setUp() {
        knowledgeRepository.deleteAll();
    }

    @Test
    void importLoadsAllBuiltinQuestions() {
        KnowledgeService.ImportSummary summary = knowledgeService.importBuiltinKnowledge();

        assertThat(summary.total()).isEqualTo(BUILTIN_TOTAL);
        assertThat(summary.inserted()).isEqualTo(BUILTIN_TOTAL);
        assertThat(summary.skipped()).isZero();
        assertThat(knowledgeRepository.count()).isEqualTo(BUILTIN_TOTAL);

        List<KnowledgeItem> items = knowledgeRepository.findAll();
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getQuestion()).isNotBlank();
            assertThat(item.getAnswer()).isNotBlank();
            assertThat(item.getCategory()).isNotBlank();
            assertThat(item.getTags()).isNotBlank();
            assertThat(item.getId()).isNotNull();
        });
        assertThat(items).extracting(KnowledgeItem::getCategory)
                .contains("Java并发", "MySQL", "Redis", "Spring", "Spring Boot", "算法");
        // 多岗位方向补齐：前端/Go/测试/运维/AI 方向分组均已导入，难度三档齐备供由浅入深出题
        assertThat(items).extracting(KnowledgeItem::getCategory)
                .contains("JavaScript基础", "Vue", "React", "前端工程化",
                        "Go语言基础", "Go并发编程",
                        "软件测试基础", "自动化与接口测试", "性能测试",
                        "Linux与Shell", "Docker与Kubernetes",
                        "大模型基础", "Prompt工程", "RAG应用", "Agent开发", "AI应用工程");
        assertThat(items).extracting(KnowledgeItem::getDifficulty)
                .contains(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD);
        // 内置题库归属官方：owner 恒为 NULL
        assertThat(items).allSatisfy(item -> assertThat(item.getOwnerUserId()).isNull());
    }

    @Test
    void reimportIsIdempotent() {
        knowledgeService.importBuiltinKnowledge();
        KnowledgeService.ImportSummary second = knowledgeService.importBuiltinKnowledge();

        assertThat(second.inserted()).isZero();
        assertThat(second.skipped()).isEqualTo(BUILTIN_TOTAL);
        assertThat(knowledgeRepository.count()).isEqualTo(BUILTIN_TOTAL);
    }
}
