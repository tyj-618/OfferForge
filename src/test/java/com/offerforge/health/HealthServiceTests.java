package com.offerforge.health;

import com.offerforge.health.HealthResponse.ComponentHealth;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康状态计算：核心组件（mysql/llm）DOWN 整体 DOWN，
 * 非核心（redis/elasticsearch）DOWN 整体 DEGRADED，DISABLED 不影响。
 */
class HealthServiceTests {

    private final HealthService healthService = new HealthService(null, null, null, null, null);

    @Test
    void allUpReturnsUp() {
        assertThat(overall(components("UP", "UP", "UP", "UP"))).isEqualTo("UP");
    }

    @Test
    void disabledComponentsDoNotDegrade() {
        assertThat(overall(components("UP", "DISABLED", "DISABLED", "UP"))).isEqualTo("UP");
    }

    @Test
    void redisDownDegradesButNotDown() {
        assertThat(overall(components("UP", "DOWN", "UP", "UP"))).isEqualTo("DEGRADED");
    }

    @Test
    void elasticsearchDownDegradesButNotDown() {
        assertThat(overall(components("UP", "UP", "DOWN", "UP"))).isEqualTo("DEGRADED");
    }

    @Test
    void mysqlDownMakesOverallDown() {
        assertThat(overall(components("DOWN", "UP", "UP", "UP"))).isEqualTo("DOWN");
    }

    @Test
    void llmDownMakesOverallDown() {
        assertThat(overall(components("UP", "UP", "UP", "DOWN"))).isEqualTo("DOWN");
    }

    @Test
    void coreDownWinsOverNonCoreDown() {
        assertThat(overall(components("DOWN", "DOWN", "DOWN", "UP"))).isEqualTo("DOWN");
    }

    private String overall(Map<String, ComponentHealth> components) {
        return healthService.overall(components);
    }

    private Map<String, ComponentHealth> components(String mysql, String redis, String es, String llm) {
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        components.put("mysql", new ComponentHealth(mysql, 1L, null));
        components.put("redis", new ComponentHealth(redis, 1L, null));
        components.put("elasticsearch", new ComponentHealth(es, 1L, null));
        components.put("llm", new ComponentHealth(llm, 1L, null));
        return components;
    }
}
