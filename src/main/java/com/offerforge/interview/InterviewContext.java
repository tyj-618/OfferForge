package com.offerforge.interview;

import com.offerforge.ai.AnswerEvaluation;
import com.offerforge.knowledge.Difficulty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试会话上下文（工作记忆，可变 POJO，便于 Jackson 序列化到 Redis）。
 * MySQL 不做持久化，会话生命周期内由 InterviewSessionStore 管理。
 */
public class InterviewContext {

    private String sessionId;
    private long userId;
    /** 面试岗位方向，用于报告展示 */
    private String position;
    /** 关联简历 id（可空）：PROJECT 阶段基于简历生成项目题 */
    private Long resumeId;
    private InterviewState state = InterviewState.OPENING;
    private String currentQuestion;
    private String currentCandidateAnswer;
    private InterviewState currentQuestionPhase;
    private String currentKnowledgePoint;
    private boolean currentQuestionFollowUp;
    /** 当前题目的追问次数（每道题上限 maxFollowUps） */
    private int currentFollowUpCount;
    /** 连续高分（>=7）次数，用于难度提升判定 */
    private int consecutiveHighScores;
    /** 连续低分（<4）次数，用于难度降低判定 */
    private int consecutiveLowScores;
    /** 当前出题难度，随连续答题表现动态调整 */
    private Difficulty currentDifficulty = Difficulty.MEDIUM;
    private Map<String, Integer> phaseQuestionCounts = new HashMap<>();
    /** 基于简历生成的备选题队列（PROJECT 项目题 / DEEP 深挖题），优先于通用题库消费 */
    private List<InterviewQuestionBank.InterviewQuestion> preparedQuestions = new ArrayList<>();
    /** 项目题是否已尝试生成（无论成败只触发一次，避免重复调 LLM） */
    private boolean projectQuestionsGenerated;
    /** 深挖题是否已尝试生成 */
    private boolean deepQuestionsGenerated;
    /** 工作记忆：每道题（含追问）的提问内容、回答、评分快照 */
    private List<QuestionRecord> questionHistory = new ArrayList<>();
    /** 本场面试累计 token 消耗（仅模型返回 usage 时累计，结束时写入日志） */
    private int inputTokens;
    private int outputTokens;
    private long createdAtEpochMillis;

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

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public InterviewState getState() {
        return state;
    }

    public void setState(InterviewState state) {
        this.state = state;
    }

    public String getCurrentQuestion() {
        return currentQuestion;
    }

    public void setCurrentQuestion(String currentQuestion) {
        this.currentQuestion = currentQuestion;
    }

    public String getCurrentCandidateAnswer() {
        return currentCandidateAnswer;
    }

    public void setCurrentCandidateAnswer(String currentCandidateAnswer) {
        this.currentCandidateAnswer = currentCandidateAnswer;
    }

    public InterviewState getCurrentQuestionPhase() {
        return currentQuestionPhase;
    }

    public void setCurrentQuestionPhase(InterviewState currentQuestionPhase) {
        this.currentQuestionPhase = currentQuestionPhase;
    }

    public String getCurrentKnowledgePoint() {
        return currentKnowledgePoint;
    }

    public void setCurrentKnowledgePoint(String currentKnowledgePoint) {
        this.currentKnowledgePoint = currentKnowledgePoint;
    }

    public boolean isCurrentQuestionFollowUp() {
        return currentQuestionFollowUp;
    }

    public void setCurrentQuestionFollowUp(boolean currentQuestionFollowUp) {
        this.currentQuestionFollowUp = currentQuestionFollowUp;
    }

    public int getCurrentFollowUpCount() {
        return currentFollowUpCount;
    }

    public void setCurrentFollowUpCount(int currentFollowUpCount) {
        this.currentFollowUpCount = currentFollowUpCount;
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

    public Difficulty getCurrentDifficulty() {
        return currentDifficulty;
    }

    public void setCurrentDifficulty(Difficulty currentDifficulty) {
        this.currentDifficulty = currentDifficulty;
    }

    public Map<String, Integer> getPhaseQuestionCounts() {
        return phaseQuestionCounts;
    }

    public void setPhaseQuestionCounts(Map<String, Integer> phaseQuestionCounts) {
        this.phaseQuestionCounts = phaseQuestionCounts;
    }

    public List<InterviewQuestionBank.InterviewQuestion> getPreparedQuestions() {
        return preparedQuestions;
    }

    public void setPreparedQuestions(List<InterviewQuestionBank.InterviewQuestion> preparedQuestions) {
        this.preparedQuestions = preparedQuestions;
    }

    public boolean isProjectQuestionsGenerated() {
        return projectQuestionsGenerated;
    }

    public void setProjectQuestionsGenerated(boolean projectQuestionsGenerated) {
        this.projectQuestionsGenerated = projectQuestionsGenerated;
    }

    public boolean isDeepQuestionsGenerated() {
        return deepQuestionsGenerated;
    }

    public void setDeepQuestionsGenerated(boolean deepQuestionsGenerated) {
        this.deepQuestionsGenerated = deepQuestionsGenerated;
    }

    public List<QuestionRecord> getQuestionHistory() {
        return questionHistory;
    }

    public void setQuestionHistory(List<QuestionRecord> questionHistory) {
        this.questionHistory = questionHistory;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public void setCreatedAtEpochMillis(long createdAtEpochMillis) {
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    /** 累计一次 LLM 调用的 token 消耗（调用在会话锁内进行，无需额外同步） */
    public void addTokenUsage(int inputTokens, int outputTokens) {
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
    }

    /**
     * 当前阶段已出题数（含正在作答的当前题）。
     */
    public int questionsInPhase(InterviewState phase) {
        return phaseQuestionCounts.getOrDefault(phase.name(), 0);
    }

    public void recordQuestionAsked(InterviewState phase) {
        phaseQuestionCounts.merge(phase.name(), 1, Integer::sum);
    }

    /**
     * 记录一次作答评估：主问题记原知识点，追问沿用当前知识点。
     */
    public void recordAnswer(String question, String userAnswer, AnswerEvaluation evaluation) {
        String knowledgePoint = currentKnowledgePoint == null ? "" : currentKnowledgePoint;
        questionHistory.add(new QuestionRecord(
                question, userAnswer, evaluation, knowledgePoint, currentQuestionFollowUp, currentQuestionPhase));
    }

    /**
     * 总共已问的题数（不含追问）。
     */
    public int totalQuestionsAsked() {
        return (int) questionHistory.stream().filter(record -> !record.isFollowUp()).count();
    }

    /**
     * 总共使用的追问次数。
     */
    public int totalFollowUpsUsed() {
        return (int) questionHistory.stream().filter(QuestionRecord::isFollowUp).count();
    }

    /**
     * 主问题平均分（追问不计入）。
     */
    public double averageScore() {
        return questionHistory.stream()
                .filter(record -> !record.isFollowUp())
                .mapToDouble(QuestionRecord::getScore)
                .average()
                .orElse(0.0);
    }

    public Double lastScore() {
        return questionHistory.isEmpty()
                ? null
                : questionHistory.get(questionHistory.size() - 1).getScore();
    }
}
