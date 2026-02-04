package com.miaomiao.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * AI服务统一配置 配置各服务商的凭证和支持的模型（按类型区分ASR/LLM/TTS）
 */

@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIServiceConfig {

    /**
     * 各服务商配置
     * key: 服务商标识 (zhipu, openai, local等)
     */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /**
     * 服务商配置（包含凭证和模型列表）
     */
    @Data
    public static class ProviderConfig {
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
         * 是否启用Token缓存
         */
        private Boolean enableTokenCache = true;

        /**
         * Token过期时间（毫秒）
         */
        private Integer tokenExpire = 3600000;

        /**
         * 支持的ASR模型列表
         */
        private Set<String> asrModels;

        /**
         * 支持的LLM模型列表
         */
        private Set<String> llmModels;

        /**
         * 支持的TTS模型列表
         */
        private Set<String> ttsModels;
    }
}
