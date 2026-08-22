package com.offerforge.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<FeedbackItem, Long> {

    Page<FeedbackItem> findAllByOrderByIdDesc(Pageable pageable);

    List<FeedbackItem> findByUserIdOrderByIdDesc(Long userId);

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);
}
