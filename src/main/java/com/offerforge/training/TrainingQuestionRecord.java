package com.offerforge.training;

/**
 * 专项训练单题作答记录（随会话上下文序列化）。
 */
public class TrainingQuestionRecord {

    private String question;
    private String knowledgePoint;
    private double score;

    public TrainingQuestionRecord() {
    }

    public TrainingQuestionRecord(String question, String knowledgePoint, double score) {
        this.question = question;
        this.knowledgePoint = knowledgePoint;
        this.score = score;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getKnowledgePoint() {
        return knowledgePoint;
    }

    public void setKnowledgePoint(String knowledgePoint) {
        this.knowledgePoint = knowledgePoint;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
