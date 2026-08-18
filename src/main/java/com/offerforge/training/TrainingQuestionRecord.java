package com.offerforge.training;

import com.offerforge.ai.AnswerEvaluation;

/**
 * 专项训练单题作答记录（随会话上下文序列化）。
 * 除题面与得分外，同步保存用户回答、导师点评与详细评估，
 * 供刷新/断线后按历史完整重建对话（参考答案不下发，避免泄露）。
 */
public class TrainingQuestionRecord {

    private String question;
    private String knowledgePoint;
    private double score;
    /** 用户作答内容（刷新恢复时回放） */
    private String answer;
    /** 导师点评全文（刷新恢复时回放） */
    private String comment;
    /** 详细评估：亮点/不足/改进回答（「具体分析」展开用，旧记录可为 null） */
    private AnswerEvaluation evaluation;

    public TrainingQuestionRecord() {
    }

    public TrainingQuestionRecord(String question, String knowledgePoint, double score) {
        this.question = question;
        this.knowledgePoint = knowledgePoint;
        this.score = score;
    }

    public TrainingQuestionRecord(String question, String knowledgePoint, double score,
                                  String answer, String comment, AnswerEvaluation evaluation) {
        this.question = question;
        this.knowledgePoint = knowledgePoint;
        this.score = score;
        this.answer = answer;
        this.comment = comment;
        this.evaluation = evaluation;
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

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public AnswerEvaluation getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(AnswerEvaluation evaluation) {
        this.evaluation = evaluation;
    }
}
