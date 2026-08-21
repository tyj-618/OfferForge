package com.offerforge.training;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 专项训练归档记录（任务 7）：训练完成/提前结束后落库的简要成绩。
 */
@Entity
@Table(name = "training_record", indexes = {
        @Index(name = "idx_training_record_user_time", columnList = "user_id, finished_at")
})
public class TrainingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** Redis 训练会话 id（业务主键） */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    /** 训练的资料分组 */
    @Column(nullable = false, length = 64)
    private String category;

    /** 实际作答题数 */
    @Column(name = "asked_count", nullable = false)
    private int askedCount;

    /** 平均得分（0-100，单题分 ×10） */
    @Column(name = "average_score", nullable = false)
    private double averageScore;

    /** 本场达到的最高难度（EASY/MEDIUM/HARD） */
    @Column(name = "max_difficulty", nullable = false, length = 16)
    private String maxDifficulty;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    /** 逐题明细 JSON（List<TrainingQuestionRecord>）：训练报告页展示；旧记录可为 null */
    @Column(name = "details_json", columnDefinition = "LONGTEXT")
    private String detailsJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getAskedCount() {
        return askedCount;
    }

    public void setAskedCount(int askedCount) {
        this.askedCount = askedCount;
    }

    public double getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(double averageScore) {
        this.averageScore = averageScore;
    }

    public String getMaxDifficulty() {
        return maxDifficulty;
    }

    public void setMaxDifficulty(String maxDifficulty) {
        this.maxDifficulty = maxDifficulty;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getDetailsJson() {
        return detailsJson;
    }

    public void setDetailsJson(String detailsJson) {
        this.detailsJson = detailsJson;
    }
}
