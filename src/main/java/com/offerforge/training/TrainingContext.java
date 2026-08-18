package com.offerforge.training;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.offerforge.knowledge.Difficulty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 专项训练会话上下文（任务 7，可变 POJO，Jackson 序列化到 Redis/内存存储）。
 * 单资料分组由浅入深训练：EASY 起步，连续高分升档、连续低分降档，达标题数完成归档。
 */
public class TrainingContext {

    /** 进行中 */
    public static final String STATE_ACTIVE = "ACTIVE";
    /** 已完成（达标题数/题库耗尽/主动结束），已归档简要成绩 */
    public static final String STATE_FINISHED = "FINISHED";

    private String sessionId;
    private long userId;
    /** 训练的资料分组（官方或本人私有） */
    private String category;
    /** 助手语气风格：strict / friendly（旧会话缺失按 friendly） */
    private String style;
    private String state = STATE_ACTIVE;
    /** 当前出题难度：EASY 起步，随连续答题表现动态调整 */
    private Difficulty currentDifficulty = Difficulty.EASY;
    /** 连续高分（>=7）次数，用于难度提升判定 */
    private int consecutiveHighScores;
    /** 连续低分（<4）次数，用于难度降低判定 */
    private int consecutiveLowScores;
    private String currentQuestion;
    private String currentKnowledgePoint;
    private String currentCandidateAnswer;
    /** 已作答题目记录（题面 + 得分），达标题数即完成 */
    private List<TrainingQuestionRecord> questionHistory = new ArrayList<>();
    /** 本场训练达到的最高难度（归档展示用） */
    private Difficulty maxDifficultyReached = Difficulty.EASY;
    private long createdAtEpochMillis;
    private Long finishedAtEpochMillis;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    /** 风格归一化：null/非法值一律按 friendly，兼容旧会话 */
    public String getStyle() {
        return com.offerforge.ai.AssistantStyle.normalize(style);
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    @JsonIgnore
    public boolean isFinished() {
        return STATE_FINISHED.equals(state);
    }

    public Difficulty getCurrentDifficulty() {
        return currentDifficulty;
    }

    public void setCurrentDifficulty(Difficulty currentDifficulty) {
        this.currentDifficulty = currentDifficulty;
        if (currentDifficulty != null && currentDifficulty.ordinal() > maxDifficultyReached.ordinal()) {
            this.maxDifficultyReached = currentDifficulty;
        }
    }

    public int getConsecutiveHighScores() {
        return consecutiveHighScores;
    }

    public void setConsecutiveHighScores(int consecutiveHighScores) {
        this.consecutiveHighScores = consecutiveHighScores;
    }

    public int getConsecutiveLowScores() {
        return consecutiveLowScores;
    }

    public void setConsecutiveLowScores(int consecutiveLowScores) {
        this.consecutiveLowScores = consecutiveLowScores;
    }

    public String getCurrentQuestion() {
        return currentQuestion;
    }

    public void setCurrentQuestion(String currentQuestion) {
        this.currentQuestion = currentQuestion;
    }

    public String getCurrentKnowledgePoint() {
        return currentKnowledgePoint;
    }

    public void setCurrentKnowledgePoint(String currentKnowledgePoint) {
        this.currentKnowledgePoint = currentKnowledgePoint;
    }

    public String getCurrentCandidateAnswer() {
        return currentCandidateAnswer;
    }

    public void setCurrentCandidateAnswer(String currentCandidateAnswer) {
        this.currentCandidateAnswer = currentCandidateAnswer;
    }

    public List<TrainingQuestionRecord> getQuestionHistory() {
        return questionHistory;
    }

    public void setQuestionHistory(List<TrainingQuestionRecord> questionHistory) {
        this.questionHistory = questionHistory;
    }

    public Difficulty getMaxDifficultyReached() {
        return maxDifficultyReached;
    }

    public void setMaxDifficultyReached(Difficulty maxDifficultyReached) {
        this.maxDifficultyReached = maxDifficultyReached;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public void setCreatedAtEpochMillis(long createdAtEpochMillis) {
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public Long getFinishedAtEpochMillis() {
        return finishedAtEpochMillis;
    }

    public void setFinishedAtEpochMillis(Long finishedAtEpochMillis) {
        this.finishedAtEpochMillis = finishedAtEpochMillis;
    }

    @JsonIgnore
    public int askedCount() {
        return questionHistory.size();
    }

    /** 平均分（0-10）；未作答返回 0 */
    @JsonIgnore
    public double averageScore() {
        if (questionHistory.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (TrainingQuestionRecord record : questionHistory) {
            total += record.getScore();
        }
        return total / questionHistory.size();
    }

    @JsonIgnore
    public Set<String> askedQuestions() {
        Set<String> asked = new HashSet<>();
        questionHistory.forEach(record -> asked.add(record.getQuestion()));
        if (currentQuestion != null) {
            asked.add(currentQuestion);
        }
        return asked;
    }
}
