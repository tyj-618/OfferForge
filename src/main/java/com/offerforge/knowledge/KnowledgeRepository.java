package com.offerforge.knowledge;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository extends JpaRepository<KnowledgeItem, Long> {

    /**
     * 官方条目去重：官方内容 owner 恒为 NULL。
     */
    Optional<KnowledgeItem> findByQuestionAndOwnerUserIdIsNull(String question);

    /**
     * 用户私有条目去重：同一用户题面不重复（不同用户互不影响）。
     */
    Optional<KnowledgeItem> findByQuestionAndOwnerUserId(String question, Long ownerUserId);

    /**
     * 我的上传列表：按 id 升序。
     */
    List<KnowledgeItem> findByOwnerUserIdOrderById(Long ownerUserId);

    /**
     * 官方题库列表：owner 恒为 NULL，按 id 升序。
     */
    List<KnowledgeItem> findByOwnerUserIdIsNullOrderById();

    /**
     * 批量删除用：仅命中本人私有条目，非本人 id 自然被排除。
     */
    List<KnowledgeItem> findByIdInAndOwnerUserId(Collection<Long> ids, Long ownerUserId);

    Optional<KnowledgeItem> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /**
     * 官方分组去重列表（owner 恒为 NULL）。
     */
    @Query("""
            select distinct k.category from KnowledgeItem k
            where k.ownerUserId is null
            order by k.category
            """)
    List<String> findOfficialCategories();

    /**
     * 可见分类去重列表：官方全局 + 本人私有。
     */
    @Query("""
            select distinct k.category from KnowledgeItem k
            where k.ownerUserId is null or k.ownerUserId = :userId
            order by k.category
            """)
    List<String> findVisibleCategories(@Param("userId") Long userId);

    /**
     * 面试出题用：按分类取可见题（官方 + 本人私有），按 id 升序保证选择确定性。
     */
    @Query("""
            select k from KnowledgeItem k
            where k.category in :categories and (k.ownerUserId is null or k.ownerUserId = :userId)
            order by k.id
            """)
    List<KnowledgeItem> findVisibleByCategories(@Param("categories") Collection<String> categories,
                                                @Param("userId") Long userId);

    /**
     * 面试出题用：按分类 + 难度取可见题，供难度控制筛选。
     */
    @Query("""
            select k from KnowledgeItem k
            where k.category in :categories and k.difficulty = :difficulty
              and (k.ownerUserId is null or k.ownerUserId = :userId)
            order by k.id
            """)
    List<KnowledgeItem> findVisibleByCategoriesAndDifficulty(@Param("categories") Collection<String> categories,
                                                             @Param("difficulty") Difficulty difficulty,
                                                             @Param("userId") Long userId);

    /**
     * 关键词检索（可见性隔离）：仅命中官方 + 本人私有条目。
     */
    @Query("""
            select k from KnowledgeItem k
            where (k.ownerUserId is null or k.ownerUserId = :userId)
              and (lower(k.question) like lower(concat('%', :keyword, '%'))
               or lower(k.tags) like lower(concat('%', :keyword, '%'))
               or lower(k.category) like lower(concat('%', :keyword, '%'))
               or lower(k.answer) like lower(concat('%', :keyword, '%')))
            """)
    List<KnowledgeItem> searchVisibleByKeyword(@Param("keyword") String keyword,
                                               @Param("userId") Long userId, Pageable pageable);

    /** 用户全部可见题 id（官方 + 本人私有）：掌握度周衰减时确定无标记题目集合 */
    @Query("""
            select k.id from KnowledgeItem k
            where k.ownerUserId is null or k.ownerUserId = :userId
            """)
    List<Long> findVisibleItemIds(@Param("userId") Long userId);
}
