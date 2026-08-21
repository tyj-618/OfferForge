package com.offerforge.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeMasteryRepository extends JpaRepository<KnowledgeMastery, Long> {

    Optional<KnowledgeMastery> findByUserIdAndKnowledgeItemId(Long userId, Long knowledgeItemId);

    /** 用户全部掌握度记录：选题加权与资料库标记展示 */
    List<KnowledgeMastery> findByUserId(Long userId);
}
