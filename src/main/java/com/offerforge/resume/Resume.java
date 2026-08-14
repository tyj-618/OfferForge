package com.offerforge.resume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 简历实体：支持一个用户多份简历。education/skills/projects/internships 为 JSON 字符串，
 * projects 反序列化为 {@link ProjectExperience} 列表。
 * <p>敏感信息策略：日志只打印 id/姓名等标识，绝不打印简历正文。</p>
 */
@Entity
@Table(name = "resume", indexes = {
        @Index(name = "idx_resume_user_updated", columnList = "user_id, updated_at")
})
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 姓名 */
    @Column(nullable = false, length = 64)
    private String name;

    /** 教育经历（JSON 字符串） */
    @Column(columnDefinition = "TEXT")
    private String education;

    /** 技能列表（JSON 字符串） */
    @Column(columnDefinition = "TEXT")
    private String skills;

    /** 项目经历（ProjectExperience 列表 JSON） */
    @Column(columnDefinition = "TEXT")
    private String projects;

    /** 实习经历（JSON 字符串） */
    @Column(columnDefinition = "TEXT")
    private String internships;

    /** 自我介绍 */
    @Column(columnDefinition = "TEXT")
    private String selfIntroduction;

    /** 原始简历文本（全文粘贴） */
    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    public String getProjects() {
        return projects;
    }

    public void setProjects(String projects) {
        this.projects = projects;
    }

    public String getInternships() {
        return internships;
    }

    public void setInternships(String internships) {
        this.internships = internships;
    }

    public String getSelfIntroduction() {
        return selfIntroduction;
    }

    public void setSelfIntroduction(String selfIntroduction) {
        this.selfIntroduction = selfIntroduction;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
