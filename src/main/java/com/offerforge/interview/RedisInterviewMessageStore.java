package com.offerforge.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.ChatMessage;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Redis 消息存储：以 List 结构存放（每条消息一个 JSON 元素），
 * RPUSH + LTRIM + EXPIRE 在同一 MULTI/EXEC 事务内执行，保证追加与窗口截断原子，
 * 避免 GET→merge→SET 在并发下互相覆盖丢消息。
 */
@Component
@Profile("redis")
public class RedisInterviewMessageStore implements InterviewMessageStore {

    private static final String KEY_PREFIX = "interview:";
    private static final String KEY_SUFFIX = ":messages";

    private final StringRedisTemplate redisTemplate;
    private final InterviewProperties properties;
    private final ObjectMapper objectMapper;

    public RedisInterviewMessageStore(StringRedisTemplate redisTemplate,
                                      InterviewProperties properties,
                                      ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = key(sessionId);
        List<String> encoded = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            try {
                encoded.add(objectMapper.writeValueAsString(message));
            } catch (JsonProcessingException exception) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试对话历史序列化失败");
            }
        }
        int window = properties.getMessageWindow();
        long ttlSeconds = properties.getSessionTtlSeconds();
        redisTemplate.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(RedisOperations<K, V> operations) throws DataAccessException {
                operations.multi();
                operations.opsForList().rightPushAll((K) key, (Collection<V>) encoded);
                // 保留尾部 window 条，与 InMemory 实现的窗口语义一致
                operations.opsForList().trim((K) key, -window, -1);
                operations.expire((K) key, Duration.ofSeconds(ttlSeconds));
                return operations.exec();
            }
        });
    }

    @Override
    public List<ChatMessage> list(String sessionId) {
        List<String> values = redisTemplate.opsForList().range(key(sessionId), 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> messages = new ArrayList<>(values.size());
        for (String value : values) {
            messages.add(fromJson(value));
        }
        return messages;
    }

    @Override
    public void clear(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private ChatMessage fromJson(String value) {
        try {
            return objectMapper.readValue(value, ChatMessage.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试对话历史反序列化失败");
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }
}
