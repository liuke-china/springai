package com.springai.springai.memory.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * IMS 项目 WebClient 配置
 *
 * key/base-url 从 application.yml 读（spring.ims.*）。
 * 改了 yml 重启即可，不需要改代码。
 */
@Configuration
public class ImsWebClientConfig {

    @Value("${spring.ims.base-url:http://localhost:8080}")
    private String imsBaseUrl;

    @Value("${spring.ims.token:}")
    private String imsToken;

    @Bean("imsWebClient")
    public WebClient imsWebClient() {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(imsBaseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json");

        if (imsToken != null && !imsToken.isBlank()) {
            builder.defaultHeader("Authorization", imsToken);
        }
        return builder.build();
    }
}
