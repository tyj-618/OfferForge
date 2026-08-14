package com.offerforge.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试综合反馈报告：评分统计由服务端计算，文字总结（亮点/薄弱点/建议）由模型生成并经服务端兜底。
 * 可变 POJO，序列化为 JSON 存入 interview_session.report_json。
 */
public class InterviewReport {

    private String interviewId;
    private long userId;
    private Instant interviewTime;
    /** 面试岗位方向 */
    private String position;
    private int totalQuestions;
    private int totalFollowUps;
    /** 面试时长（分钟） */
    private int durationMinutes;

    /** 综合评分（0-100，主问题平均分 × 10） */
    private double overallScore;
    /** 评级：优秀/良好/及格/需努力 */
    private String rating;
    /** 各维度平均分（0-10） */
    private double avgAccuracy;
    private double avgCompleteness;
    private double avgClarity;
    private double avgDepth;

    /** 各阶段主问题平均分（0-10，无题目时为 0） */
    private double basicsScore;
    private double projectScore;
    private double deepScore;

    private List<QuestionEvaluation> questionEvaluations = new ArrayList<>();
    private List<String> strengths = new ArrayList<>();
    private List<String> weaknesses = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private List<RecommendedMaterial> recommendedMaterials = new ArrayList<>();

    public String getInterviewId() {
        return interviewId;
    }

    public void setInterviewId(String interviewId) {
        this.interviewId = interviewId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public Instant getInterviewTime() {
        return interviewTime;
    }

    public void setInterviewTime(Instant interviewTime) {
        this.interviewTime = interviewTime;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public int getTotalFollowUps() {
        return totalFollowUps;
    }

    public void setTotalFollowUps(int totalFollowUps) {
        this.totalFollowUps = totalFollowUps;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(double overallScore) {
        this.overallScore = overallScore;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public double getAvgAccuracy() {
        return avgAccuracy;
    }

    public void setAvgAccuracy(double avgAccuracy) {
        this.avgAccuracy = avgAccuracy;
    }

    public double getAvgCompleteness() {
        return avgCompleteness;
    }

    public void setAvgCompleteness(double avgCompleteness) {
        this.avgCompleteness = avgCompleteness;
    }

    public double getAvgClarity() {
        return avgClarity;
    }

    public void setAvgClarity(double avgClarity) {
        this.avgClarity = avgClarity;
    }

    public double getAvgDepth() {
        return avgDepth;
    }

    public void setAvgDepth(double avgDepth) {
        this.avgDepth = avgDepth;
    }

    public double getBasicsScore() {
        return basicsScore;
    }

    public void setBasicsScore(double basicsScore) {
        this.basicsScore = basicsScore;
    }

    public double getProjectScore() {
        return projectScore;
    }

    public void setProjectScore(double projectScore) {
        this.projectScore = projectScore;
    }

    public double getDeepScore() {
        return deepScore;
    }

    public void setDeepScore(double deepScore) {
        this.deepScore = deepScore;
    }

    public List<QuestionEvaluation> getQuestionEvaluations() {
        return questionEvaluations;
    }

    public void setQuestionEvaluations(List<QuestionEvaluation> questionEvaluations) {
        this.questionEvaluations = questionEvaluations;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public List<RecommendedMaterial> getRecommendedMaterials() {
        return recommendedMaterials;
    }

    public void setRecommendedMaterials(List<RecommendedMaterial> recommendedMaterials) {
        this.recommendedMaterials = recommendedMaterials;
    }
}
