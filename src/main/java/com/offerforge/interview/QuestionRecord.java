package com.offerforge.interview;

/**
 * 工作记忆条目：一道题（或一次追问）的提问内容、用户回答、评分与上下文快照。
 * 可变 POJO，便于 Jackson 序列化到 Redis。
 */
public class QuestionRecord {

    private String question;
    private String userAnswer;
    private double score;
    private String knowledgePoint;
    private boolean followUp;
    private InterviewState state;

    public QuestionRecord() {
    }

    public QuestionRecord(String question, String userAnswer, double score,
                          String knowledgePoint, boolean followUp, InterviewState state) {
        this.question = question;
        this.userAnswer = userAnswer;
        this.score = score;
        this.knowledgePoint = knowledgePoint;
        this.followUp = followUp;
        this.state = state;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getUserAnswer() {
        return userAnswer;
    }

    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getKnowledgePoint() {
        return knowledgePoint;
    }

    public void setKnowledgePoint(String knowledgePoint) {
        this.knowledgePoint = knowledgePoint;
    }

    public boolean isFollowUp() {
        return followUp;
    }

    public void setFollowUp(boolean followUp) {
        this.followUp = followUp;
    }

    public InterviewState getState() {
        return state;
    }

    public void setState(InterviewState state) {
        this.state = state;
    }
}
