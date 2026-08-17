package com.offerforge.knowledge;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.resume.ResumeRequest;
import com.offerforge.resume.ResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资料库归属隔离集成测试：官方全局共享、用户私有仅本人可见、上传/列表/删除归属校验。
 */
@ActiveProfiles("test")
@SpringBootTest
class KnowledgeOwnershipTests {

    private static final Long USER_A = 901L;
    private static final Long USER_B = 902L;

    private static final String MARKED_CONTENT = """
            Q: 私有题：消息队列如何保证顺序消费？
            A: 单分区有序 + 顺序消费线程。
            """;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeRepository knowledgeRepository;

    @Autowired
    private ResumeService resumeService;

    @BeforeEach
    void setUp() {
        knowledgeRepository.deleteAll();
        knowledgeService.importBuiltinKnowledge();
    }

    @Test
    void privateItemsAreOnlyVisibleToOwner() {
        knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, null);

        // 本人可检索到私有题；他人检索不到；官方题双方均可见
        assertThat(knowledgeService.search(USER_A, "顺序消费", 5))
                .extracting(RetrievedKnowledge::question)
                .anyMatch(question -> question.contains("顺序消费"));
        assertThat(knowledgeService.search(USER_B, "顺序消费", 5))
                .extracting(RetrievedKnowledge::question)
                .noneMatch(question -> question.contains("顺序消费"));
        assertThat(knowledgeService.search(USER_B, "HashMap", 5)).isNotEmpty();
    }

    @Test
    void listMineAndCategoriesAreIsolatedPerUser() {
        knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, "内部八股");

        List<KnowledgeService.OwnedKnowledge> mineA = knowledgeService.listMine(USER_A);
        assertThat(mineA).hasSize(1);
        assertThat(mineA.get(0).category()).isEqualTo("内部八股");
        assertThat(knowledgeService.listMine(USER_B)).isEmpty();

        KnowledgeService.CategoriesView viewA = knowledgeService.visibleCategories(USER_A);
        assertThat(viewA.official()).contains("Java基础", "Redis");
        assertThat(viewA.custom()).containsExactly("内部八股");
        // 自定义分组仅归属上传者可见
        assertThat(knowledgeService.visibleCategories(USER_B).custom()).doesNotContain("内部八股");
    }

    @Test
    void deleteOwnedRejectsOtherUsersItems() {
        knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, null);
        Long itemId = knowledgeService.listMine(USER_A).get(0).id();

        assertThatThrownBy(() -> knowledgeService.deleteOwned(USER_B, itemId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        knowledgeService.deleteOwned(USER_A, itemId);
        assertThat(knowledgeService.listMine(USER_A)).isEmpty();
    }

    @Test
    void duplicateQuestionWithinSameOwnerIsSkipped() {
        KnowledgeService.UploadSummary first = knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, null);
        KnowledgeService.UploadSummary second = knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, null);

        assertThat(first.inserted()).isEqualTo(1);
        assertThat(second.inserted()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        // 不同用户可上传同题面（归属维度唯一，互不冲突）
        assertThat(knowledgeService.uploadKnowledge(USER_B, "b.md", MARKED_CONTENT, null).inserted()).isEqualTo(1);
    }

    @Test
    void uploadDefaultsToCustomCategoryAndRejectsUnparsableContent() {
        knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, null);
        assertThat(knowledgeService.listMine(USER_A).get(0).category())
                .isEqualTo(KnowledgeService.DEFAULT_CUSTOM_CATEGORY);

        assertThatThrownBy(() -> knowledgeService.uploadKnowledge(USER_A, "bad.md", "没有问答标记的普通文本", null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);

        assertThatThrownBy(() -> knowledgeService.uploadKnowledge(USER_A, "a.md", MARKED_CONTENT, "分".repeat(65)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void recommendCategoriesScoresByResumeKeywords() {
        resumeService.save(USER_A, new ResumeRequest(null, "张三", null,
                "Java 并发编程、MySQL 索引优化、Redis 缓存、Spring Boot 微服务", null, null, null, null));

        List<String> recommended = knowledgeService.recommendCategories(USER_A, null);

        assertThat(recommended).contains("MySQL", "Redis", "Java并发", "Spring Boot");
        // 无简历用户推荐结果为空
        assertThat(knowledgeService.recommendCategories(USER_B, null)).isEmpty();
    }
}
