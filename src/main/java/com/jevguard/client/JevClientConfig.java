package com.jevguard.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按 key 是否存在选择真实/Mock 客户端（PRD §4.8）。 */
@Configuration
public class JevClientConfig {

    private static final Logger log = LoggerFactory.getLogger(JevClientConfig.class);

    @Bean
    public JevClient jevClient(
            @Value("${jevguard.typesafe.base-url}") String baseUrl,
            @Value("${jevguard.typesafe.api-key:}") String apiKey,
            @Value("${jevguard.typesafe.model}") String model,
            @Value("${jevguard.typesafe.timeout-ms}") int timeoutMs) {
        if (apiKey == null || apiKey.isBlank()) {
            return new MockJevClient();
        }
        log.info("TypeSafe Jev client enabled, model={}", model);
        return new TypeSafeJevClient(baseUrl, apiKey, model, timeoutMs);
    }
}
