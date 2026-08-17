package com.offerforge.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisInterviewSessionStore implements InterviewSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisInterviewSessionStore.class);

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

    /**
     * SCAN 全部会话 key，批量 MGET 取值后逐个反序列化判断；单用户会话数极少，成本可接受。
     * 单条反序列化失败跳过，不影响其余会话的判断。
     */
    @Override
    public boolean hasActiveSession(Long userId) {
        return findActiveSession(userId).isPresent();
    }

    @Override
    public Optional<InterviewContext> findActiveSession(Long userId) {
        ScanOptions options = ScanOptions.scanOptions().match("interview:*" + KEY_SUFFIX).count(100).build();
        List<String> keys = new ArrayList<>();
        try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
                connection -> connection.scan(options))) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Optional.empty();
        }
        InterviewContext latest = null;
        for (String json : values) {
            if (json == null) {
                continue;
            }
            try {
                InterviewContext context = objectMapper.readValue(json, InterviewContext.class);
                if (context.getUserId() == userId && !context.getState().terminal()
                        && (latest == null || context.getCreatedAtEpochMillis() > latest.getCreatedAtEpochMillis())) {
                    latest = context;
                }
            } catch (JsonProcessingException exception) {
                log.warn("skip malformed session context during active session scan");
            }
        }
        return Optional.ofNullable(latest);
    }

    private String key(String sessionId) {
        return "interview:" + sessionId + KEY_SUFFIX;
    }
}
