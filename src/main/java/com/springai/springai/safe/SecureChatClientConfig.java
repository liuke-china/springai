package com.springai.springai.safe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全 Advisor 装配配置
 *
 * 把 SecurityAdvisor 作为 Bean 暴露。Spring AI 会自动把容器里所有 Advisor Bean
 * 注册为 ChatClient.Builder 的"默认 Advisor"——即对所有 ChatClient 生效（含 ImsAskController）。
 *
 * 【默认开启】app.security.advisor.enabled 缺省即为 true（matchIfMissing=true），
 * 满足"上生产前安全包默认开"的硬要求；如需临时关闭，yml 显式配 false 即可。
 *
 * 注意 Text-to-SQL 路径的特殊处理：
 *   SecurityAdvisor 的 before() 会把"用户输入"整体包进分隔符做 L1 隔离，
 *   而 text2sql 的 user prompt 是拼好的工程化 prompt（含 schema/few-shot），
 *   直接挂 Advisor 会污染 SQL 生成。因此 text2sql 不走 Advisor 形态，
 *   而是在 TextToSqlOrchestrator 入口直接用 SecurityAdvisor 的核心 API
 *   （PromptInjectionDetector + InputSanitizer）对"原始 question"做检测+清洗——
 *   既让防注入默认生效，又不污染拼好的 SQL prompt。
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)   // P1-⑩：让 app.security.* 配置生效
public class SecureChatClientConfig {

    @Bean
    @ConditionalOnProperty(name = "app.security.advisor.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityAdvisor securityAdvisor(SecurityProperties props) {
        // P1-⑩：blockMalicious 从 yml 读，灰度期可改 false（仅告警不拦截）
        return new SecurityAdvisor(props.isBlockMalicious());
    }
}
