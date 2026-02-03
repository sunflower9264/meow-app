package com.miaomiao.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Provider configuration properties
 */
@Data
@ConfigurationProperties(prefix = "provider")
public class ProviderProperties {

    private VADConfig vad = new VADConfig();
    private ASRConfig asr = new ASRConfig();
    private LLMConfig llm = new LLMConfig();
    private TTSConfig tts = new TTSConfig();

    @Data
    public static class VADConfig {
        private String defaultProvider = "silero";
        private ProviderConfig silero = new ProviderConfig();
    }

    @Data
    public static class ASRConfig {
        private String defaultProvider = "funasr";
        private ProviderConfig funasr = new ProviderConfig();
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig aliyun = new ProviderConfig();
    }

    @Data
    public static class LLMConfig {
        private String defaultProvider = "openai";
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig aliyun = new ProviderConfig();
        private ProviderConfig ollama = new ProviderConfig();
        private ProviderConfig chatglm = new ProviderConfig();
    }

    @Data
    public static class TTSConfig {
        private String defaultProvider = "edge";
        private ProviderConfig edge = new ProviderConfig();
        private ProviderConfig aliyun = new ProviderConfig();
        private ProviderConfig openai = new ProviderConfig();
    }

    @Data
    public static class ProviderConfig {
        private boolean enabled = true;
        private String apiKey;
        private String baseUrl;
        private String serviceUrl;
        private String model;
        private String voice;
        private String language;
        private Double temperature;
        private Double threshold;
        private Double thresholdLow;
        private Integer maxTokens;
        private String appKey;
        private String accessKeyId;
        private String accessKeySecret;
        // TTS specific
        private String rate;
        private String pitch;
        private String volume;
    }
}
