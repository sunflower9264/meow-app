package com.miaomiao.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * AI服务统一配置
 * 只负责配置各服务商的凭证信息（API Key、BaseURL等）
 * 具体使用哪个模型由调用时指定，不由配置决定
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIServiceConfig {

    /**
     * 各服务商配置
     * key: 服务商标识 (zhipu, openai, local等)
     */
    private Map<String, ProviderCredential> providers = new HashMap<>();

    /**
     * 服务商凭证配置
     */
    @Data
    public static class ProviderCredential {
        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * API密钥
         */
        private String apiKey;

        /**
         * API基础URL
         */
        private String baseUrl;

        /**
         * 是否启用Token缓存（部分SDK支持）
         */
        private Boolean enableTokenCache = true;

        /**
         * Token过期时间（毫秒）
         */
        private Integer tokenExpire = 3600000;
    }
}
