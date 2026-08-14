package com.offerforge.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository extends JpaRepository<KnowledgeItem, Long> {

    Optional<KnowledgeItem> findByQuestion(String question);

    /**
     * 面试出题用：按分类取题，按 id 升序保证选择确定性。
     */
    List<KnowledgeItem> findByCategoryInOrderById(Collection<String> categories);

    /**
     * 面试出题用：按分类 + 难度取题，供难度控制筛选。
     */
    List<KnowledgeItem> findByCategoryInAndDifficultyOrderById(Collection<String> categories, Difficulty difficulty);

    @Query("""
            select k from KnowledgeItem k
            where lower(k.question) like lower(concat('%', :keyword, '%'))
               or lower(k.tags) like lower(concat('%', :keyword, '%'))
               or lower(k.category) like lower(concat('%', :keyword, '%'))
               or lower(k.answer) like lower(concat('%', :keyword, '%'))
            """)
    List<KnowledgeItem> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
