package com.springai.springai.safe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全包统一配置（app.security.*）。
 *
 * P1-⑩ 新增：让 SecurityAdvisor 的 blockMalicious 可配，便于灰度——
 * 例如先用 blockMalicious=false 上线观察命中量，再切 true 拦截。
 *
 * yml 示例：
 *   app:
 *     security:
 *       block-malicious: true   # true=拦截恶意提示注入；false=仅日志告警不拦截（灰度期用）
 *       advisor:
 *         enabled: true
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** 命中恶意注入时是否真的拦截。默认 true（直接拒），灰度期可改 false（只记日志） */
    private boolean blockMalicious = true;

    /** 兼容旧配置：app.security.advisor.enabled（保持不变） */
    private Advisor advisor = new Advisor();

    public boolean isBlockMalicious() { return blockMalicious; }
    public void setBlockMalicious(boolean blockMalicious) { this.blockMalicious = blockMalicious; }

    public Advisor getAdvisor() { return advisor; }
    public void setAdvisor(Advisor advisor) { this.advisor = advisor; }

    public static class Advisor {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
