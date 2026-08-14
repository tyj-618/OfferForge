package com.offerforge.ai;

import java.io.IOException;
import java.util.List;

public interface AiModelClient {

    AiTextResult generateText(List<ChatMessage> messages);

    /**
     * 流式生成：逐块回调，异常时抛出 IOException 或 BusinessException。
     */
    void generateStream(List<ChatMessage> messages, AiStreamChunkConsumer chunkConsumer) throws IOException;

    /**
     * 对候选人回答评分（0-10）；candidateAnswer 为参考答案，可为 null（项目类问题无标准答案）。
     */
    AiEvaluation evaluateAnswer(String question, String candidateAnswer, String userAnswer);

    /**
     * 结构化评估：四维度评分 + 应覆盖/遗漏/错误要点 + 点评；candidateAnswer 与 knowledgePoint 可为 null。
     * 实现需保证返回值合法（分值 0-10、列表非 null），解析失败时返回中间档兜底。
     */
    AnswerEvaluation evaluateAnswerDetail(String question, String knowledgePoint, String candidateAnswer, String userAnswer);

    /**
     * 追问生成：基于原题与评估发现的遗漏/错误要点，生成同知识点换角度的追问题面（纯文本）。
     */
    String generateFollowUpQuestion(String prompt);

    /**
     * 面试报告文本摘要：基于逐题评估记录生成亮点/薄弱点/改进建议文本。
     * 评分与统计始终由服务端计算，本方法只产出文字总结；解析失败时返回 null 由调用方兜底。
     */
    ReportSummary generateReportSummary(String prompt);

    /**
     * 简历纯文本解析：将简历原文结构化为 JSON（字段同简历创建请求）。
     * 解析失败时返回 null，由调用方兜底（仅保存原文）。
     */
    String parseResume(String rawText);

    /**
     * 基于简历项目经历生成 2-3 个面试问题：解析 {questions:[...]} JSON。
     * 解析失败时返回空列表，由调用方降级到通用项目题。
     */
    List<AiGeneratedQuestion> generateProjectQuestions(String prompt);

    /**
     * 基于 PROJECT 阶段的问题与回答生成一道深挖题：解析单对象 JSON。
     * 解析失败时返回 null，由调用方降级到知识库出题。
     */
    AiGeneratedQuestion generateDeepQuestion(String prompt);
}
