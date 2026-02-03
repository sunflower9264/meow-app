package com.miaomiao.assistant.session.provider.config;

import lombok.Data;

/**
 * ASR Provider Configuration
 */
@Data
public class ASRConfig {
    private boolean enabled = true;
    private String apiKey;
    private String baseUrl;
    private String model;
    private String language;
    private String appKey;
    private String accessKeyId;
    private String accessKeySecret;
}
