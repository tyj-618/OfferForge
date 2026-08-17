package com.offerforge.training;

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

/**
 * Redis 专项训练会话存储：长 TTL（默认 24h）支持刷新/暂离恢复。
 */
@Component
@Profile("redis")
public class RedisTrainingSessionStore implements TrainingSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTrainingSessionStore.class);

    /** 会话上下文 key：training:{sessionId}:context */
    private static final String KEY_PREFIX = "training:";
    private static final String KEY_SUFFIX = ":context";

    private final StringRedisTemplate redisTemplate;
    private final TrainingProperties properties;
    private final ObjectMapper objectMapper;

    public RedisTrainingSessionStore(StringRedisTemplate redisTemplate,
                                     TrainingProperties properties,
                                     ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(TrainingContext context) {
        try {
            redisTemplate.opsForValue().set(
                    key(context.getSessionId()),
                    objectMapper.writeValueAsString(context),
                    Duration.ofSeconds(properties.getSessionTtlSeconds()));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "专项训练会话序列化失败");
        }
    }

    @Override
    public Optional<TrainingContext> find(String sessionId) {
        String json = redisTemplate.opsForValue().get(key(sessionId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TrainingContext.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "专项训练会话反序列化失败");
        }
    }

    @Override
    public void remove(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    /**
     * SCAN 全部训练会话 key 后逐个反序列化判断；长 TTL 下会话数仍然极少，成本可接受。
     */
    @Override
    public boolean hasActiveSession(Long userId) {
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*" + KEY_SUFFIX).count(100).build();
        List<String> keys = new ArrayList<>();
        try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
                connection -> connection.scan(options))) {
            while (cursor.hasNext()) {
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }
        if (keys.isEmpty()) {
            return false;
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return false;
        }
        for (String json : values) {
            if (json == null) {
                continue;
            }
            try {
                TrainingContext context = objectMapper.readValue(json, TrainingContext.class);
                if (context.getUserId() == userId && !context.isFinished()) {
                    return true;
                }
            } catch (JsonProcessingException exception) {
                log.warn("skip malformed training session context during active session scan");
            }
        }
        return false;
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }
}
