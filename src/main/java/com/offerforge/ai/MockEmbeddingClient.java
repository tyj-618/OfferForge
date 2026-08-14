package com.offerforge.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性哈希向量实现，仅用于测试与无 Embedding 服务的本地开发：
 * 相同/相似文本产生高余弦相似度的向量，保证检索链路可测。
 */
@Component
@ConditionalOnProperty(prefix = "offerforge.search", name = "embedding-provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingClient implements EmbeddingClient {

    private static final Pattern LATIN_PATTERN = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private final SearchProperties properties;

    public MockEmbeddingClient(SearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Float> embed(String text) {
        int dimensions = properties.getEmbeddingDimensions();
        double[] vector = new double[dimensions];
        for (String token : tokenize(text)) {
            int index = Math.floorMod(token.hashCode(), dimensions);
            vector[index] += 1.0;
        }
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            vector[0] = 1.0;
        } else {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        List<Float> result = new ArrayList<>(dimensions);
        for (double value : vector) {
            result.add((float) value);
        }
        return result;
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher latinMatcher = LATIN_PATTERN.matcher(lower);
        while (latinMatcher.find()) {
            tokens.add(latinMatcher.group());
        }
        Matcher cjkMatcher = CJK_PATTERN.matcher(lower);
        while (cjkMatcher.find()) {
            String run = cjkMatcher.group();
            tokens.add(run);
            for (int i = 0; i + 2 <= run.length(); i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return tokens;
    }
}
