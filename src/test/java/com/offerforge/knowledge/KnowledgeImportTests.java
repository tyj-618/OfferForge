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

        assertThat(summary.total()).isEqualTo(50);
        assertThat(summary.inserted()).isEqualTo(50);
        assertThat(summary.skipped()).isZero();
        assertThat(knowledgeRepository.count()).isEqualTo(50);

        List<KnowledgeItem> items = knowledgeRepository.findAll();
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getQuestion()).isNotBlank();
            assertThat(item.getAnswer()).isNotBlank();
            assertThat(item.getCategory()).isNotBlank();
            assertThat(item.getTags()).isNotBlank();
            assertThat(item.getId()).isNotNull();
        });
        assertThat(items).extracting(KnowledgeItem::getCategory)
                .contains("Java并发", "MySQL", "Redis", "Spring");
    }

    @Test
    void reimportIsIdempotent() {
        knowledgeService.importBuiltinKnowledge();
        KnowledgeService.ImportSummary second = knowledgeService.importBuiltinKnowledge();

        assertThat(second.inserted()).isZero();
        assertThat(second.skipped()).isEqualTo(50);
        assertThat(knowledgeRepository.count()).isEqualTo(50);
    }
}
