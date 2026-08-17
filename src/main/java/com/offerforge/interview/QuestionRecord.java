package com.offerforge.interview;

import com.offerforge.ai.AnswerEvaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作记忆条目：一道题（或一次追问）的提问内容、用户回答、评估详情与上下文快照。
 * 可变 POJO，便于 Jackson 序列化到 Redis。
 */
public class QuestionRecord {

    private String question;
    private String userAnswer;
    /** 综合评分（0-10，四维度加权） */
    private double score;
    private double accuracy;
    private double completeness;
    private double clarity;
    private double depth;
    private String feedback;
    private List<String> keyPoints = new ArrayList<>();
    private List<String> missedPoints = new ArrayList<>();
    private List<String> wrongPoints = new ArrayList<>();
    private String knowledgePoint;
    private boolean followUp;
    /** 深度训练子流程题目（followUp 同为 true）：不计入主流程已问题数/平均分/追问统计；旧数据缺省 false */
    private boolean deepTraining;
    private InterviewState state;

    public QuestionRecord() {
    }

    public QuestionRecord(String question, String userAnswer, AnswerEvaluation evaluation,
                          String knowledgePoint, boolean followUp, InterviewState state) {
        this.question = question;
        this.userAnswer = userAnswer;
        this.score = evaluation.overall();
        this.accuracy = evaluation.accuracy();
        this.completeness = evaluation.completeness();
        this.clarity = evaluation.clarity();
        this.depth = evaluation.depth();
        this.feedback = evaluation.feedback();
        this.keyPoints = new ArrayList<>(evaluation.keyPoints());
        this.missedPoints = new ArrayList<>(evaluation.missedPoints());
        this.wrongPoints = new ArrayList<>(evaluation.wrongPoints());
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

    public double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(double accuracy) {
        this.accuracy = accuracy;
    }

    public double getCompleteness() {
        return completeness;
    }

    public void setCompleteness(double completeness) {
        this.completeness = completeness;
    }

    public double getClarity() {
        return clarity;
    }

    public void setClarity(double clarity) {
        this.clarity = clarity;
    }

    public double getDepth() {
        return depth;
    }

    public void setDepth(double depth) {
        this.depth = depth;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }

    public void setKeyPoints(List<String> keyPoints) {
        this.keyPoints = keyPoints;
    }

    public List<String> getMissedPoints() {
        return missedPoints;
    }

    public void setMissedPoints(List<String> missedPoints) {
        this.missedPoints = missedPoints;
    }

    public List<String> getWrongPoints() {
        return wrongPoints;
    }

    public void setWrongPoints(List<String> wrongPoints) {
        this.wrongPoints = wrongPoints;
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

    public boolean isDeepTraining() {
        return deepTraining;
    }

    public void setDeepTraining(boolean deepTraining) {
        this.deepTraining = deepTraining;
    }

    public InterviewState getState() {
        return state;
    }

    public void setState(InterviewState state) {
        this.state = state;
    }
}
