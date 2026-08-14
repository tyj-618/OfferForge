package com.offerforge.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 面试会话持久化记录：面试结束后落库，保存基础统计与完整报告 JSON。
 * 进行中的会话由 Redis/内存会话存储管理，本表只做终态归档。
 */
@Entity
@Table(name = "interview_session", indexes = {
        @Index(name = "idx_interview_session_user_time", columnList = "user_id, start_time")
})
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** Redis 会话 id，报告查询以此为业务主键 */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 64)
    private String position;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    /** 归档状态，当前固定 FINISHED */
    @Column(nullable = false, length = 32)
    private String status;

    /** 综合评分（0-100） */
    @Column(name = "overall_score", nullable = false)
    private double overallScore;

    /** 完整反馈报告（InterviewReport JSON） */
    @Column(name = "report_json", nullable = false, columnDefinition = "TEXT")
    private String reportJson;

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

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(double overallScore) {
        this.overallScore = overallScore;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }
}
