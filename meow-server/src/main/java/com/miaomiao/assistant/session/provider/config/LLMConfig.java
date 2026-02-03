package com.miaomiao.assistant.session.provider.config;

import lombok.Data;

/**
 * LLM Provider Configuration
 */
@Data
public class LLMConfig {
    private boolean enabled = true;
    private String apiKey;
    private String baseUrl;
    private String model;
    private Double temperature;
    private Integer maxTokens;
}
