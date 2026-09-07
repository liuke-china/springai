package com.springai.springai.metrics;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RAG 业务健康检查（可观测性学习目标）
 * =====================================================================
 * 实现 Spring Boot Actuator 的 HealthIndicator 接口，把 RAG 存储层（PGVector 表 chat_document）
 * 纳入 /actuator/health，组件名自动取 bean 名去后缀 → "rag"
 * （bean 名 ragHealthIndicator → 去掉 HealthIndicator → 组件名 rag）。
 *
 * 设计取舍：只检查表可访问（SELECT count(*) FROM chat_document），不实际调 LLM。
 * 原因：健康检查会被监控/探活频繁调用，真去 ping 外部 LLM 服务 会白白消耗 token 额度且引入外部依赖抖动。
 * 若需"LLM 探活"，可在此加一个轻量 chatClient.call() 但默认不建议开启。
 */
@Component
public class RagHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public RagHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer docCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM chat_document", Integer.class);
            return Health.up()
                    .withDetail("ragStore", "chat_document")
                    .withDetail("docCount", docCount)
                    .build();
        } catch (Exception e) {
            // 表不存在 / PG 未连 / 尚未 ingest：RAG 存储未就绪
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
