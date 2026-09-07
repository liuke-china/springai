package com.springai.springai.safe;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 数据库最小权限配置（L3 纵深防御的最后一道物理防线）
 *
 * 问题：你项目里 Text-to-SQL / 记忆都用同一个管理员账号（postgres/123456）连 PG。
 * 这意味着：只要上层"prompt 限 SELECT" + "SQL 关键字校验"任何一层被绕过，就能真删库。
 *
 * 企业做法：为"只读查询场景"单独建一个**只授 SELECT 的数据库账号**，
 * 让 JdbcTemplate 用它连。这样即使所有应用层防护都失效，数据库本身也会拒绝写操作。
 * —— 这就是"纵深防御"：不指望任何单层百分百可靠。
 *
 * 默认关闭（enabled=false），避免你还没建只读角色时启动报错。
 * 上线前：先执行下面的 SQL 建角色，再把 application.yml 里加 app.security.readonly-db.enabled=true。
 */
@Configuration
public class DbLeastPrivilegeConfig {

    /**
     * 显式声明默认写入 DataSource，避免新增只读 DataSource 后覆盖 Spring Boot 的主数据源。
     */
    @Bean
    @Primary
    public DriverManagerDataSource writeDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}") String driverClassName) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(driverClassName);
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    @Primary
    public JdbcTemplate writeJdbcTemplate(@Qualifier("writeDataSource") DriverManagerDataSource writeDataSource) {
        return new JdbcTemplate(writeDataSource);
    }

    /*
     * ===== 在 PostgreSQL 里由 DBA 执行（不是应用代码）=====
     * CREATE ROLE ims_readonly LOGIN PASSWORD '请换成强密码';
     * GRANT CONNECT ON DATABASE postgres TO ims_readonly;
     * GRANT USAGE ON SCHEMA public TO ims_readonly;
     * GRANT SELECT ON ALL TABLES IN SCHEMA public TO ims_readonly;
     * ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ims_readonly;
     * -- 显式撤销写权限（新角色默认无写权，这里再保险一次）
     * REVOKE INSERT, UPDATE, DELETE, TRUNCATE, DROP ON ALL TABLES IN SCHEMA public FROM ims_readonly;
     *
     * 然后把这个只读 DataSource 接给 TextToSqlController 的 JdbcTemplate，
     * 替换掉当前共享的管理员账号。
     */

    @Bean
    @ConditionalOnProperty(name = "app.security.readonly-db.enabled", havingValue = "true")
    public DriverManagerDataSource readOnlyDataSource(
            @Value("${app.security.readonly-db.url:${spring.datasource.url}}") String url,
            @Value("${app.security.readonly-db.username:ims_readonly}") String username,
            @Value("${app.security.readonly-db.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    @Qualifier("readOnlyJdbcTemplate")
    @ConditionalOnProperty(name = "app.security.readonly-db.enabled", havingValue = "true")
    public JdbcTemplate readOnlyJdbcTemplate(
            @Qualifier("readOnlyDataSource") DriverManagerDataSource readOnlyDataSource) {
        return new JdbcTemplate(readOnlyDataSource);
    }
}
