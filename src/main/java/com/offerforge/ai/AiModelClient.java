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
     * 带凭据生成：credentials 非空时切换用户自带 Key，null 使用系统配置。
     * 默认实现绑定 {@link LlmCallContext} 后委托单参方法，Mock 等实现无需感知凭据。
     */
    default AiTextResult generateText(List<ChatMessage> messages, LlmCredentials credentials) {
        LlmCredentials previous = LlmCallContext.current();
        LlmCallContext.bind(credentials);
        try {
            return generateText(messages);
        } finally {
            LlmCallContext.bind(previous);
        }
    }

    /**
     * 带凭据流式生成：语义同 {@link #generateText(List, LlmCredentials)}。
     */
    default void generateStream(List<ChatMessage> messages, AiStreamChunkConsumer chunkConsumer,
                                LlmCredentials credentials) throws IOException {
        LlmCredentials previous = LlmCallContext.current();
        LlmCallContext.bind(credentials);
        try {
            generateStream(messages, chunkConsumer);
        } finally {
            LlmCallContext.bind(previous);
        }
    }

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
     * 带详细反馈的结构化评估：detailed=true 时要求模型额外产出
     * goodPoints/badPoints/improvedAnswer（训练模式详细反馈与深度训练使用），
     * 解析失败时置 null/空。默认实现忽略 detailed，Mock 等实现无需感知。
     */
    default AnswerEvaluation evaluateAnswerDetail(String question, String knowledgePoint, String candidateAnswer,
                                                  String userAnswer, boolean detailed) {
        return evaluateAnswerDetail(question, knowledgePoint, candidateAnswer, userAnswer);
    }

    /**
     * 开场自我介绍结构化评估：不对照知识点标准答案，按信息完整度/表达结构/岗位相关性评估；
     * 需携带 goodPoints/badPoints/improvedAnswer 详细反馈；返回值保证合法，解析失败时返回 null 由调用方兜底。
     * 默认实现返回 null，Mock 等实现无需感知。
     */
    default AnswerEvaluation evaluateIntroDetail(String intro, String position) {
        return null;
    }

    /**
     * 追问生成：基于原题与评估发现的遗漏/错误要点，生成同知识点换角度的追问题面（纯文本）。
     */
    String generateFollowUpQuestion(String prompt);

    /**
     * 开场自我介绍信息完备性检查：信息充分时返回 null/空白，
     * 不足时返回一条向候选人索取缺失信息的补充提问。
     */
    default String generateIntroFollowUp(String prompt) {
        return null;
    }

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

    /**
     * 健康探测：发起一次最小生成请求验证模型链路可用；异常视为不可用。
     */
    default boolean healthProbe() {
        try {
            generateText(List.of(ChatMessage.user("ping")));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
