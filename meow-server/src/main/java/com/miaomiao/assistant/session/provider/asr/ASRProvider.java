package com.miaomiao.assistant.session.provider.asr;

import com.miaomiao.assistant.session.provider.config.ASRConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * ASR (Automatic Speech Recognition) Provider Abstract Base Class
 * Supports both streaming and non-streaming speech recognition
 */
@Slf4j
public abstract class ASRProvider {

    // Common configuration fields
    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected String language = "zh";

    /**
     * Convert speech to text (non-streaming)
     *
     * @param audioData Audio bytes data
     * @param format    Audio format (e.g., "wav", "opus", "mp3")
     * @return Recognized text
     */
    public abstract String speechToText(byte[] audioData, String format);

    /**
     * Convert speech to text asynchronously (non-streaming)
     *
     * @param audioData Audio bytes data
     * @param format    Audio format (e.g., "wav", "opus", "mp3")
     * @return CompletableFuture with recognized text
     */
    public abstract CompletableFuture<String> speechToTextAsync(byte[] audioData, String format);

    /**
     * Process audio chunk for streaming ASR
     *
     * @param audioChunk Audio chunk data
     * @param isLast     Whether this is the last chunk
     * @return Partial or final recognized text
     */
    public abstract String processAudioChunk(byte[] audioChunk, boolean isLast);

    /**
     * Start a new streaming ASR session
     *
     * @return Session ID for this streaming session
     */
    public abstract String startStreamingSession();

    /**
     * End a streaming ASR session
     *
     * @param sessionId Session ID to end
     * @return Final recognized text
     */
    public abstract String endStreamingSession(String sessionId);

    /**
     * Get the provider name
     *
     * @return Provider name (e.g., "aliyun", "openai", "baidu")
     */
    public abstract String getProviderName();

    /**
     * Check if this provider supports streaming
     *
     * @return true if streaming is supported
     */
    public abstract boolean isStreamingSupported();

    /**
     * Initialize the provider with configuration
     */
    public void initialize(ASRConfig config) {
        if (config.getApiKey() != null) {
            this.apiKey = config.getApiKey();
        }
        if (config.getBaseUrl() != null) {
            this.baseUrl = config.getBaseUrl();
        }
        if (config.getModel() != null) {
            this.model = config.getModel();
        }
        if (config.getLanguage() != null) {
            this.language = config.getLanguage();
        }
        log.info("{} ASR provider initialized: baseUrl={}, language={}", 
                getProviderName(), baseUrl, language);
    }

    // Getters for subclasses
    protected String getApiKey() {
        return apiKey;
    }

    protected String getBaseUrl() {
        return baseUrl;
    }

    protected String getModel() {
        return model;
    }

    protected String getLanguage() {
        return language;
    }
}
