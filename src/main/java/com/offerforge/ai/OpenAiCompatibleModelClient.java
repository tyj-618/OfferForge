package com.offerforge.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "offerforge.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleModelClient implements AiModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("SCORE\\s*[:：]\\s*(\\d+)");

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleModelClient(AiProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        String baseUrl = properties.getBaseUrl();
        this.restClient = RestClient.builder()
                .baseUrl(isBlank(baseUrl) ? "http://localhost" : baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public AiTextResult generateText(List<ChatMessage> messages) {
        ensureConfigured();
        long startedAt = System.currentTimeMillis();
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                String responseBody = restClient.post()
                        .uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(buildRequestBody(messages))
                        .retrieve()
                        .body(String.class);
                ProviderResponse response = parseProviderResponse(responseBody);
                if (response == null || response.choices() == null || response.choices().isEmpty()
                        || response.choices().get(0).message() == null) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型服务返回格式异常");
                }
                String content = response.choices().get(0).message().content();
                String requestId = response.id() == null || response.id().isBlank()
                        ? UUID.randomUUID().toString() : response.id();
                Integer inputTokens = response.usage() == null ? null : response.usage().inputTokens();
                Integer outputTokens = response.usage() == null ? null : response.usage().outputTokens();
                log.info("qa stage=llm mode=text providerRequestId={} model={} elapsedMs={} inputTokens={} outputTokens={}",
                        requestId, properties.getModel(), System.currentTimeMillis() - startedAt,
                        inputTokens, outputTokens);
                return new AiTextResult(content == null ? "" : content, requestId, inputTokens, outputTokens);
            } catch (RestClientResponseException exception) {
                if (isRetryable(exception) && attempt < properties.getMaxRetries()) {
                    backoff(attempt);
                    continue;
                }
                log.warn("qa stage=llm mode=text status=failed model={} httpStatus={} elapsedMs={}",
                        properties.getModel(), exception.getStatusCode().value(),
                        System.currentTimeMillis() - startedAt);
                throw unavailable();
            } catch (ResourceAccessException exception) {
                if (attempt < properties.getMaxRetries()) {
                    backoff(attempt);
                    continue;
                }
                log.warn("qa stage=llm mode=text status=unavailable model={} elapsedMs={}",
                        properties.getModel(), System.currentTimeMillis() - startedAt);
                throw unavailable();
            }
        }
        throw unavailable();
    }

    Map<String, Object> buildRequestBody(List<ChatMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages.stream()
                .map(message -> Map.of("role", message.providerRole(), "content", message.content()))
                .toList());
        body.put("temperature", 0.2);
        body.put("max_tokens", properties.getMaxOutputTokens());
        return body;
    }

    @Override
    public void generateStream(List<ChatMessage> messages, AiStreamChunkConsumer chunkConsumer) throws IOException {
        ensureConfigured();
        long startedAt = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            URI endpoint = URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/chat/completions");
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout((int) Duration.ofSeconds(properties.getTimeoutSeconds()).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(properties.getStreamReadTimeoutSeconds()).toMillis());
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
            connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
            byte[] requestBody = objectMapper.writeValueAsBytes(buildStreamRequestBody(messages));
            try (var output = connection.getOutputStream()) {
                output.write(requestBody);
            }

            int status = connection.getResponseCode();
            if (status >= 400) {
                log.warn("qa stage=llm mode=stream status=failed model={} httpStatus={}",
                        properties.getModel(), status);
                throw unavailable();
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        log.info("qa stage=llm mode=stream status=completed model={} elapsedMs={}",
                                properties.getModel(), System.currentTimeMillis() - startedAt);
                        return;
                    }
                    emitDeltaContent(data, chunkConsumer);
                }
            }
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型服务流式返回格式异常");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public AiEvaluation evaluateAnswer(String question, String candidateAnswer, String userAnswer) {
        String prompt = """
                你是 Java 后端面试官评分器。请根据面试题与候选人回答给出 0-10 的整数评分。
                输出格式必须为：SCORE: 分数 换行 COMMENT: 一句话简评。
                面试题：%s
                参考答案：%s
                候选人回答：%s
                """.formatted(
                question,
                candidateAnswer == null || candidateAnswer.isBlank() ? "（无标准答案，按项目经验与表达评估）" : candidateAnswer,
                userAnswer == null ? "" : userAnswer);
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        Matcher matcher = SCORE_PATTERN.matcher(result.content());
        if (!matcher.find()) {
            log.warn("qa stage=llm mode=evaluate status=unparsable model={} requestId={}",
                    properties.getModel(), result.requestId());
            return new AiEvaluation(5, "评分解析失败，按中等处理");
        }
        int score;
        try {
            // \d+ 不限位数，超过 Integer.MAX_VALUE 时 parseInt 会抛 NFE，需先解析再做范围钳制
            score = Math.max(0, Math.min(10, Integer.parseInt(matcher.group(1))));
        } catch (NumberFormatException exception) {
            log.warn("qa stage=llm mode=evaluate status=score-overflow model={} requestId={}",
                    properties.getModel(), result.requestId());
            score = 5;
        }
        return new AiEvaluation(score, result.content());
    }

    @Override
    public AnswerEvaluation evaluateAnswerDetail(String question, String knowledgePoint, String candidateAnswer, String userAnswer) {
        String prompt = """
                你是一个资深技术面试官，正在评估候选人的回答。

                问题：%s
                考察知识点：%s
                标准答案要点：%s
                候选人回答：%s

                请从以下维度评分（0-10）：
                1. 准确性（accuracy）：回答中的技术事实是否正确，有无明显错误
                2. 完整性（completeness）：是否覆盖了标准答案中的关键要点
                3. 表达清晰度（clarity）：回答是否条理清晰、逻辑连贯
                4. 深度（depth）：是否有深入分析、原理剖析或延伸思考，而非仅停留在表面

                请返回 JSON 格式：
                {
                  "accuracy": 数字,
                  "completeness": 数字,
                  "clarity": 数字,
                  "depth": 数字,
                  "overall": 数字,
                  "keyPoints": ["应覆盖的关键要点1"],
                  "missedPoints": ["遗漏的要点1"],
                  "wrongPoints": ["错误的说法1"],
                  "feedback": "2-3句话的综合点评，指出亮点和不足"
                }
                只输出 JSON 本身，不要输出其他内容。
                """.formatted(
                question,
                knowledgePoint == null || knowledgePoint.isBlank() ? "（未指定）" : knowledgePoint,
                referenceText(candidateAnswer),
                userAnswer == null ? "" : userAnswer);
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        AnswerEvaluation parsed = parseAnswerEvaluation(result.content());
        if (parsed == null) {
            log.warn("qa stage=llm mode=evaluate-detail status=unparsable model={} requestId={}",
                    properties.getModel(), result.requestId());
            return new AnswerEvaluation(5, 5, 5, 5, 5, List.of(), List.of(), List.of(), "评估结果解析失败，按中等处理");
        }
        return parsed;
    }

    @Override
    public String generateFollowUpQuestion(String prompt) {
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        String content = result.content() == null ? "" : result.content().trim();
        return content.isEmpty() ? "你能针对这个知识点再展开讲讲吗？" : content;
    }

    @Override
    public ReportSummary generateReportSummary(String prompt) {
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        ReportSummary parsed = parseReportSummary(result.content());
        if (parsed == null) {
            log.warn("qa stage=llm mode=report-summary status=unparsable model={} requestId={}",
                    properties.getModel(), result.requestId());
        }
        return parsed;
    }

    @Override
    public String parseResume(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String prompt = """
                你是简历解析器。请将以下简历纯文本结构化，返回 JSON：
                {
                  "name": "姓名",
                  "education": "教育经历文本",
                  "skills": "技能清单文本",
                  "projects": [
                    {"projectName": "项目名称", "role": "角色", "duration": "时间段",
                     "description": "项目描述", "techStack": "技术栈",
                     "highlights": "亮点成果", "challenges": "遇到的挑战"}
                  ],
                  "internships": "实习经历文本",
                  "selfIntroduction": "自我介绍"
                }
                无法识别的字段置为空字符串或空数组；只输出 JSON 本身。
                简历原文：
                %s
                """.formatted(rawText);
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        String json = extractJsonObject(result.content() == null ? "" : result.content());
        if (json == null) {
            log.warn("qa stage=llm mode=resume-parse status=unparsable model={} requestId={}",
                    properties.getModel(), result.requestId());
        }
        return json;
    }

    @Override
    public List<AiGeneratedQuestion> generateProjectQuestions(String prompt) {
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        List<AiGeneratedQuestion> parsed = parseGeneratedQuestions(result.content());
        if (parsed.isEmpty()) {
            log.warn("qa stage=llm mode=project-questions status=unparsable model={} requestId={}",
                    properties.getModel(), result.requestId());
        }
        return parsed;
    }

    @Override
    public AiGeneratedQuestion generateDeepQuestion(String prompt) {
        AiTextResult result = generateText(List.of(ChatMessage.user(prompt)));
        AiGeneratedQuestion parsed = parseGeneratedQuestion(result.content());
        if (parsed == null) {
            log.warn("qa stage=llm mode=deep-question status=unparsable model={} requestId={}",
                    properties.getModel(), result.requestId());
        }
        return parsed;
    }

    private String referenceText(String candidateAnswer) {
        return candidateAnswer == null || candidateAnswer.isBlank()
                ? "（无标准答案，按项目经验与表达评估）" : candidateAnswer;
    }

    /**
     * 解析评估 JSON：四维度钳制 0-10，overall 由服务端按权重 0.35/0.25/0.20/0.20 重算（不信任模型自报值）。
     * 解析失败返回 null，由调用方兜底。
     */
    AnswerEvaluation parseAnswerEvaluation(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            double accuracy = clampScore(node, "accuracy");
            double completeness = clampScore(node, "completeness");
            double clarity = clampScore(node, "clarity");
            double depth = clampScore(node, "depth");
            double overall = Math.round(
                    (accuracy * 0.35 + completeness * 0.25 + clarity * 0.20 + depth * 0.20) * 10.0) / 10.0;
            String feedback = node.path("feedback").asText("");
            return new AnswerEvaluation(accuracy, completeness, clarity, depth, overall,
                    readPoints(node, "keyPoints"), readPoints(node, "missedPoints"), readPoints(node, "wrongPoints"),
                    feedback.isBlank() ? "评估完成" : feedback);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
     * 解析报告摘要 JSON：只取文本清单（每类最多 5 条）；格式非法或三类全空返回 null，由调用方兜底。
     */
    ReportSummary parseReportSummary(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> strengths = readPoints(node, "strengths", 5);
            List<String> weaknesses = readPoints(node, "weaknesses", 5);
            List<String> suggestions = readPoints(node, "suggestions", 5);
            if (strengths.isEmpty() && weaknesses.isEmpty() && suggestions.isEmpty()) {
                return null;
            }
            return new ReportSummary(strengths, weaknesses, suggestions);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
     * 解析项目题生成结果：{questions:[...]}；题面为空则丢弃，非法 JSON 返回空列表。
     */
    List<AiGeneratedQuestion> parseGeneratedQuestions(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String json = extractJsonObject(content);
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode questions = node.path("questions");
            if (!questions.isArray()) {
                return List.of();
            }
            List<AiGeneratedQuestion> parsed = new ArrayList<>();
            for (JsonNode item : questions) {
                AiGeneratedQuestion question = toGeneratedQuestion(item);
                if (question != null) {
                    parsed.add(question);
                }
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 解析单对象深挖题生成结果；题面为空或非法 JSON 返回 null。
     */
    AiGeneratedQuestion parseGeneratedQuestion(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            return toGeneratedQuestion(objectMapper.readTree(json));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private AiGeneratedQuestion toGeneratedQuestion(JsonNode node) {
        String question = node.path("question").asText("").trim();
        if (question.isEmpty()) {
            return null;
        }
        String knowledgePoint = node.path("knowledgePoint").asText("").trim();
        String difficulty = node.path("difficulty").asText("").trim().toUpperCase();
        return new AiGeneratedQuestion(question, knowledgePoint, readPoints(node, "referenceAnswer", 8), difficulty);
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : null;
    }

    private double clampScore(JsonNode node, String field) {
        double value = node.path(field).isNumber() ? node.path(field).asDouble() : 5.0;
        return Math.max(0, Math.min(10, value));
    }

    private List<String> readPoints(JsonNode node, String field) {
        return readPoints(node, field, 10);
    }

    private List<String> readPoints(JsonNode node, String field, int limit) {
        List<String> points = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            for (JsonNode item : array) {
                String text = item.asText("").trim();
                if (!text.isEmpty() && points.size() < limit) {
                    points.add(text);
                }
            }
        }
        return points;
    }

    Map<String, Object> buildStreamRequestBody(List<ChatMessage> messages) {
        Map<String, Object> body = buildRequestBody(messages);
        body.put("stream", true);
        return body;
    }

    private void emitDeltaContent(String data, AiStreamChunkConsumer chunkConsumer) throws IOException {
        try {
            ProviderStreamChunk chunk = objectMapper.readValue(data, ProviderStreamChunk.class);
            if (chunk.choices() == null || chunk.choices().isEmpty() || chunk.choices().get(0).delta() == null) {
                return;
            }
            String delta = chunk.choices().get(0).delta().content();
            if (delta != null && !delta.isEmpty()) {
                chunkConsumer.accept(delta);
            }
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型服务流式返回格式异常");
        }
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private ProviderResponse parseProviderResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, ProviderResponse.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型服务返回格式异常");
        }
    }

    private void ensureConfigured() {
        if (isBlank(properties.getBaseUrl()) || isBlank(properties.getApiKey()) || isBlank(properties.getModel())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型服务未完成配置");
        }
    }

    private boolean isRetryable(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == 429 || status >= 500;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(200L * (attempt + 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "智能问答服务暂不可用，请稍后再试");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProviderMessage(String role, String content) {
    }

    private record ProviderResponse(String id, List<ProviderChoice> choices, ProviderUsage usage) {
    }

    private record ProviderChoice(ProviderMessage message) {
    }

    private record ProviderStreamChunk(List<ProviderStreamChoice> choices) {
    }

    private record ProviderStreamChoice(ProviderMessage delta) {
    }

    private record ProviderUsage(
            @JsonProperty("prompt_tokens") Integer inputTokens,
            @JsonProperty("completion_tokens") Integer outputTokens
    ) {
    }
}
