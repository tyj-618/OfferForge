package com.offerforge.health;

import com.offerforge.ai.AiModelClient;
import com.offerforge.health.HealthResponse.ComponentHealth;
import com.offerforge.knowledge.KnowledgeIndexClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 组件健康探测：MySQL/LLM 为核心（任一 DOWN 整体 DOWN），
 * Redis/ES 为非核心（DOWN 时整体 DEGRADED，运行时分别降级为内存存储与 SQL 检索）。
 * 未启用的组件（默认无 redis profile、search.enabled=false）报 DISABLED。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final Set<String> CORE_COMPONENTS = Set.of("mysql", "llm");

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final ObjectProvider<KnowledgeIndexClient> indexClientProvider;
    private final AiModelClient aiModelClient;
    private final Environment environment;

    public HealthService(DataSource dataSource,
                         StringRedisTemplate redisTemplate,
                         ObjectProvider<KnowledgeIndexClient> indexClientProvider,
                         AiModelClient aiModelClient,
                         Environment environment) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.indexClientProvider = indexClientProvider;
        this.aiModelClient = aiModelClient;
        this.environment = environment;
    }

    public HealthResponse check() {
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        components.put("mysql", probeMysql());
        components.put("redis", probeRedis());
        components.put("elasticsearch", probeElasticsearch());
        components.put("llm", probeLlm());
        return new HealthResponse(overall(components), components);
    }

    /** 核心组件任一 DOWN → DOWN；否则非核心 DOWN → DEGRADED；其余 UP */
    String overall(Map<String, ComponentHealth> components) {
        boolean coreDown = components.entrySet().stream()
                .filter(entry -> CORE_COMPONENTS.contains(entry.getKey()))
                .anyMatch(entry -> "DOWN".equals(entry.getValue().status()));
        if (coreDown) {
            return "DOWN";
        }
        boolean nonCoreDown = components.entrySet().stream()
                .filter(entry -> !CORE_COMPONENTS.contains(entry.getKey()))
                .anyMatch(entry -> "DOWN".equals(entry.getValue().status()));
        return nonCoreDown ? "DEGRADED" : "UP";
    }

    private ComponentHealth probeMysql() {
        long startedAt = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            return ComponentHealth.up(System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            log.warn("health mysql down: {}", exception.getMessage());
            return ComponentHealth.down(exception.getMessage());
        }
    }

    private ComponentHealth probeRedis() {
        if (!environment.acceptsProfiles(Profiles.of("redis"))) {
            return ComponentHealth.disabled();
        }
        long startedAt = System.currentTimeMillis();
        try {
            redisTemplate.opsForValue().get("offerforge:health:ping");
            return ComponentHealth.up(System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            log.warn("health redis down: {}", exception.getMessage());
            return ComponentHealth.down(exception.getMessage());
        }
    }

    private ComponentHealth probeElasticsearch() {
        KnowledgeIndexClient indexClient = indexClientProvider.getIfAvailable();
        if (indexClient == null) {
            return ComponentHealth.disabled();
        }
        long startedAt = System.currentTimeMillis();
        try {
            indexClient.ping();
            return ComponentHealth.up(System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            log.warn("health elasticsearch down: {}", exception.getMessage());
            return ComponentHealth.down(exception.getMessage());
        }
    }

    private ComponentHealth probeLlm() {
        long startedAt = System.currentTimeMillis();
        if (aiModelClient.healthProbe()) {
            return ComponentHealth.up(System.currentTimeMillis() - startedAt);
        }
        log.warn("health llm down");
        return ComponentHealth.down("AI 服务不可用");
    }
}
