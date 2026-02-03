package com.miaomiao.assistant.session.provider.llm;

import com.miaomiao.assistant.session.provider.config.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * LLM (Large Language Model) Provider Abstract Base Class
 * Supports streaming and non-streaming text generation
 */
@Slf4j
public abstract class LLMProvider {

    // Common configuration fields
    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected Double defaultTemperature = 0.7;
    protected Integer defaultMaxTokens = 2000;

    /**
     * LLM response result
     */
    public static class LLMResponse {
        private String text;
        private boolean finished;
        private Map<String, Object> metadata;

        public LLMResponse(String text, boolean finished) {
            this.text = text;
            this.finished = finished;
        }

        public LLMResponse(String text, boolean finished, Map<String, Object> metadata) {
            this.text = text;
            this.finished = finished;
            this.metadata = metadata;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public boolean isFinished() {
            return finished;
        }

        public void setFinished(boolean finished) {
            this.finished = finished;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * Chat message
     */
    public static class ChatMessage {
        private String role;    // system, user, assistant
        private String content;
        private String name;    // Optional name for the message

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * Generate response (non-streaming)
     *
     * @param messages    Conversation history
     * @param temperature Sampling temperature
     * @param maxTokens   Maximum tokens to generate
     * @return Generated text
     */
    public abstract String response(List<ChatMessage> messages, Double temperature, Integer maxTokens);

    /**
     * Generate streaming response
     *
     * @param messages    Conversation history
     * @param temperature Sampling temperature
     * @param maxTokens   Maximum tokens to generate
     * @return Flux of LLMResponse chunks
     */
    public abstract Flux<LLMResponse> responseStream(List<ChatMessage> messages, Double temperature, Integer maxTokens);

    /**
     * Get the provider name
     *
     * @return Provider name (e.g., "openai", "aliyun", "ollama")
     */
    public abstract String getProviderName();

    /**
     * Initialize the provider with configuration
     * Subclasses can override to add provider-specific initialization
     */
    public void initialize(LLMConfig config) {
        if (config.getApiKey() != null) {
            this.apiKey = config.getApiKey();
        }
        if (config.getBaseUrl() != null) {
            this.baseUrl = config.getBaseUrl();
        }
        if (config.getModel() != null) {
            this.model = config.getModel();
        }
        if (config.getTemperature() != null) {
            this.defaultTemperature = config.getTemperature();
        }
        if (config.getMaxTokens() != null) {
            this.defaultMaxTokens = config.getMaxTokens();
        }
        log.info("{} LLM provider initialized: model={}, temperature={}, maxTokens={}", 
                getProviderName(), model, defaultTemperature, defaultMaxTokens);
    }

    /**
     * Get the default temperature from config
     */
    public Double getDefaultTemperature() {
        return defaultTemperature;
    }

    /**
     * Get the default max tokens from config
     */
    public Integer getDefaultMaxTokens() {
        return defaultMaxTokens;
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
}
