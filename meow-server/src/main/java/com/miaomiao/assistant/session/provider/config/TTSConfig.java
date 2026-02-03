package com.miaomiao.assistant.session.provider.config;

import lombok.Data;

/**
 * TTS Provider Configuration
 */
@Data
public class TTSConfig {
    private boolean enabled = true;
    private String apiKey;
    private String baseUrl;
    private String serviceUrl;
    private String model;
    private String voice;
    private String appKey;
    private String accessKeyId;
    private String accessKeySecret;
    // Edge TTS specific
    private String rate = "+0%";
    private String pitch = "+0Hz";
    private String volume = "+0%";
}
