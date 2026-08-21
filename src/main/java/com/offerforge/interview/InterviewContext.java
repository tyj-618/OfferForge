package com.offerforge.interview;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /** 训练模式：每题评分+点评，追问由用户选择是否深入 */
    public static final String MODE_TRAINING = "training";
    /** 实战模式：模拟真实面试节奏，追问自动进行（缺省模式） */
    public static final String MODE_PRACTICE = "practice";

    private String sessionId;
    private long userId;
    /** 面试岗位方向，用于报告展示 */
    private String position;
    /** 关联简历 id（可空）：PROJECT 阶段基于简历生成项目题 */
    private Long resumeId;
    /** 简历摘要缓存：实战模式首次出题时构建，供面试官话术自然转场（旧会话为 null） */
    private String resumeSummary;
    /** 面试模式：training / practice（旧会话反序列化为 null 时按 practice 处理） */
    private String mode;
    /** 助手语气风格：strict / friendly（旧会话缺失按 friendly） */
    private String style;
    /** 勾选的资料分组（可空）：非空时 BASICS/DEEP 出题仅用这些分组（旧会话为 null 按阶段默认） */
    private List<String> selectedCategories;
    /** 是否包含算法手写编程题：开启后 DEEP 阶段按难度掺入算法分组（旧会话缺失默认 false） */
    private boolean includeAlgorithm;
    /** 开场话术缓存：刷新恢复时凭 status 还原开场白（旧会话为 null，前端降级默认文案） */
    private String openingMessage;
    /** 回合评估中标记：answer() 开始置 true 并落库，正常/异常路径均复位；刷新恢复的前端凭此轮询等待未完成回合 */
    private boolean evaluating;
    private InterviewState state = InterviewState.OPENING;
    private String currentQuestion;
    private String currentCandidateAnswer;
    private InterviewState currentQuestionPhase;
    private String currentKnowledgePoint;
    private boolean currentQuestionFollowUp;
    /** 当前题目的追问次数（每道题上限 maxFollowUps） */
    private int currentFollowUpCount;
    /** 已废弃：旧版训练模式暂存追问（系统判定触发深度训练）；新流程不再写入，仅为存量 Redis 会话反序列化兼容保留 */
    private String pendingFollowUpQuestion;
    /** 深度训练：进入前的主面试阶段，退出/达标后恢复 */
    private InterviewState deepTrainingReturnState;
    /** 深度训练：已出递进题数（上限 maxDeepTrainingQuestions） */
    private int deepTrainingAsked;
    /** 深度训练：连续达标题数（≥ deepTrainingPassScore 累计，低分清零） */
    private int deepTrainingConsecutivePass;
    /** 开场环节：自我介绍信息不全时的补充提问次数（上限 maxOpeningFollowUps，防无限追问） */
    private int openingFollowUpCount;
    /** 连续高分（>=7）次数，用于难度提升判定 */
    private int consecutiveHighScores;
    /** 连续低分（<4）次数，用于难度降低判定 */
    private int consecutiveLowScores;
    /** 当前出题难度：由浅入深从 EASY 起步，随连续答题表现动态调整 */
    private Difficulty currentDifficulty = Difficulty.EASY;
    private Map<String, Integer> phaseQuestionCounts = new HashMap<>();
    /** 基于简历生成的备选题队列（PROJECT 项目题 / DEEP 深挖题），优先于通用题库消费 */
    private List<InterviewQuestionBank.InterviewQuestion> preparedQuestions = new ArrayList<>();
    /** 项目题是否已尝试生成（无论成败只触发一次，避免重复调 LLM） */
    private boolean projectQuestionsGenerated;
    /** 深挖题是否已尝试生成 */
    private boolean deepQuestionsGenerated;
    /** 工作记忆：每道题（含追问）的提问内容、回答、评分快照 */
    private List<QuestionRecord> questionHistory = new ArrayList<>();
    /** 「已掌握」pass 掉的题面：不计分不入 history，单独登记防重复出题（旧会话缺失为 null） */
    private java.util.Set<String> passedQuestions;
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

    public String getResumeSummary() {
        return resumeSummary;
    }

    public void setResumeSummary(String resumeSummary) {
        this.resumeSummary = resumeSummary;
    }

    /** 模式归一化：null/非法值一律按 practice，兼容旧会话 */
    public String getMode() {
        return MODE_TRAINING.equals(mode) ? MODE_TRAINING : MODE_PRACTICE;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 风格归一化：null/非法值一律按 friendly，兼容旧会话 */
    public String getStyle() {
        return com.offerforge.ai.AssistantStyle.normalize(style);
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public List<String> getSelectedCategories() {
        return selectedCategories;
    }

    public void setSelectedCategories(List<String> selectedCategories) {
        this.selectedCategories = selectedCategories;
    }

    public boolean isIncludeAlgorithm() {
        return includeAlgorithm;
    }

    public void setIncludeAlgorithm(boolean includeAlgorithm) {
        this.includeAlgorithm = includeAlgorithm;
    }

    public String getOpeningMessage() {
        return openingMessage;
    }

    public void setOpeningMessage(String openingMessage) {
        this.openingMessage = openingMessage;
    }

    public boolean isEvaluating() {
        return evaluating;
    }

    public void setEvaluating(boolean evaluating) {
        this.evaluating = evaluating;
    }

    /** 便捷判断，非持久化字段（避免序列化后反序列化失败） */
    @JsonIgnore
    public boolean isTrainingMode() {
        return MODE_TRAINING.equals(getMode());
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

    public String getPendingFollowUpQuestion() {
        return pendingFollowUpQuestion;
    }

    public void setPendingFollowUpQuestion(String pendingFollowUpQuestion) {
        this.pendingFollowUpQuestion = pendingFollowUpQuestion;
    }

    public InterviewState getDeepTrainingReturnState() {
        return deepTrainingReturnState;
    }

    public void setDeepTrainingReturnState(InterviewState deepTrainingReturnState) {
        this.deepTrainingReturnState = deepTrainingReturnState;
    }

    public int getDeepTrainingAsked() {
        return deepTrainingAsked;
    }

    public void setDeepTrainingAsked(int deepTrainingAsked) {
        this.deepTrainingAsked = deepTrainingAsked;
    }

    public int getDeepTrainingConsecutivePass() {
        return deepTrainingConsecutivePass;
    }

    public void setDeepTrainingConsecutivePass(int deepTrainingConsecutivePass) {
        this.deepTrainingConsecutivePass = deepTrainingConsecutivePass;
    }

    public int getOpeningFollowUpCount() {
        return openingFollowUpCount;
    }

    public void setOpeningFollowUpCount(int openingFollowUpCount) {
        this.openingFollowUpCount = openingFollowUpCount;
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

    public java.util.Set<String> getPassedQuestions() {
        return passedQuestions;
    }

    public void setPassedQuestions(java.util.Set<String> passedQuestions) {
        this.passedQuestions = passedQuestions;
    }

    /** 登记一道「已掌握」pass 的题：下次选题时排除，防止未入 history 导致重复出题 */
    public void recordPassedQuestion(String question) {
        if (question == null) {
            return;
        }
        if (passedQuestions == null) {
            passedQuestions = new java.util.HashSet<>();
        }
        passedQuestions.add(question);
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
        recordAnswer(question, userAnswer, evaluation, false);
    }

    /**
     * 记录一次作答评估；deepTraining=true 时标记为深度训练题，不计入主流程统计。
     */
    public void recordAnswer(String question, String userAnswer, AnswerEvaluation evaluation, boolean deepTraining) {
        String knowledgePoint = currentKnowledgePoint == null ? "" : currentKnowledgePoint;
        QuestionRecord record = new QuestionRecord(
                question, userAnswer, evaluation, knowledgePoint, currentQuestionFollowUp, currentQuestionPhase);
        record.setDeepTraining(deepTraining);
        questionHistory.add(record);
    }

    /**
     * 总共已问的题数（不含追问）。
     */
    public int totalQuestionsAsked() {
        return (int) questionHistory.stream().filter(record -> !record.isFollowUp()).count();
    }

    /**
     * 总共使用的追问次数（深度训练题不计入）。
     */
    public int totalFollowUpsUsed() {
        return (int) questionHistory.stream()
                .filter(record -> record.isFollowUp() && !record.isDeepTraining())
                .count();
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
