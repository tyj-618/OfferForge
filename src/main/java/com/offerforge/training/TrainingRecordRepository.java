package com.offerforge.training;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainingRecordRepository extends JpaRepository<TrainingRecord, Long> {

    /** 我的训练历史：按完成时间倒序 */
    List<TrainingRecord> findByUserIdOrderByFinishedAtDesc(Long userId);

    /** 我的训练历史（分页）：按完成时间倒序 */
    Page<TrainingRecord> findByUserIdOrderByFinishedAtDesc(Long userId, Pageable pageable);
}
