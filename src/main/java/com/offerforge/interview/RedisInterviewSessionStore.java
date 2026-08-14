package com.offerforge.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisInterviewSessionStore implements InterviewSessionStore {

    /** 会话上下文 key：interview:{sessionId}:context（与 messages key 同前缀风格） */
    private static final String KEY_SUFFIX = ":context";

    private final StringRedisTemplate redisTemplate;
    private final InterviewProperties properties;
    private final ObjectMapper objectMapper;

    public RedisInterviewSessionStore(StringRedisTemplate redisTemplate,
                                      InterviewProperties properties,
                                      ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(InterviewContext context) {
        try {
            redisTemplate.opsForValue().set(
                    key(context.getSessionId()),
                    objectMapper.writeValueAsString(context),
                    Duration.ofSeconds(properties.getSessionTtlSeconds()));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试会话序列化失败");
        }
    }

    @Override
    public Optional<InterviewContext> find(String sessionId) {
        String json = redisTemplate.opsForValue().get(key(sessionId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, InterviewContext.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "面试会话反序列化失败");
        }
    }

    @Override
    public void remove(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return "interview:" + sessionId + KEY_SUFFIX;
    }
}
