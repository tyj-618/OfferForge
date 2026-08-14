package com.offerforge.health;

import java.util.Map;

/**
 * 健康检查响应：status 取值 UP / DEGRADED / DOWN；
 * 核心组件（mysql、llm）任一 DOWN 则 DOWN，非核心（redis、elasticsearch）DOWN 则 DEGRADED。
 * 未启用的组件报 DISABLED，不影响整体状态。
 */
public record HealthResponse(String status, Map<String, ComponentHealth> components) {

    public record ComponentHealth(String status, Long latencyMs, String error) {

        public static ComponentHealth up(long latencyMs) {
            return new ComponentHealth("UP", latencyMs, null);
        }

        public static ComponentHealth down(String error) {
            return new ComponentHealth("DOWN", null, error);
        }

        public static ComponentHealth disabled() {
            return new ComponentHealth("DISABLED", null, null);
        }
    }
}
