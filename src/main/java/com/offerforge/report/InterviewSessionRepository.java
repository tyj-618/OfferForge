package com.offerforge.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    Optional<InterviewSession> findBySessionId(String sessionId);

    Optional<InterviewSession> findByUserIdAndSessionId(Long userId, String sessionId);

    /**
     * 历史面试列表：按开始时间倒序分页；进步曲线取前 N 条后由服务层反转为正序。
     */
    Page<InterviewSession> findByUserIdOrderByStartTimeDesc(Long userId, Pageable pageable);

    /** 按模式过滤的历史面试列表：按开始时间倒序分页（训练/实战记录划分展示） */
    Page<InterviewSession> findByUserIdAndModeOrderByStartTimeDesc(Long userId, String mode, Pageable pageable);
}
