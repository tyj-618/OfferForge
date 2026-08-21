package com.offerforge.knowledge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface KnowledgeMasteryRepository extends JpaRepository<KnowledgeMastery, Long> {

    /** 悲观行锁：recordMark 读-改-写在事务内串行化，避免并发丢失更新（须在事务中调用） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KnowledgeMastery> findByUserIdAndKnowledgeItemId(Long userId, Long knowledgeItemId);

    /** 用户全部掌握度记录：选题加权与资料库标记展示 */
    List<KnowledgeMastery> findByUserId(Long userId);
}
