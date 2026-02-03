package com.miaomiao.assistant.session.provider.tts;

import com.miaomiao.assistant.session.provider.config.TTSConfig;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * TTS (Text-to-Speech) Provider Abstract Base Class
 * Supports streaming and non-streaming audio generation
 */
@Slf4j
public abstract class TTSProvider {

    // Common configuration fields
    protected String apiKey;
    protected String baseUrl;
    protected String serviceUrl;
    protected String model;
    protected String voice;
    protected String rate = "+0%";
    protected String pitch = "+0Hz";
    protected String volume = "+0%";

    /**
     * TTS audio result
     */
    public static class TTSAudio {
        private byte[] audioData;
        private String format;        // Audio format (e.g., "mp3", "wav", "opus")
        private boolean finished;
        private boolean isSentenceEnd;
        private String text;          // The text that generated this audio

        public TTSAudio() {
        }

        public TTSAudio(byte[] audioData, String format, boolean finished) {
            this.audioData = audioData;
            this.format = format;
            this.finished = finished;
        }

        public byte[] getAudioData() {
            return audioData;
        }

        public void setAudioData(byte[] audioData) {
            this.audioData = audioData;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public boolean isFinished() {
            return finished;
        }

        public void setFinished(boolean finished) {
            this.finished = finished;
        }

        public boolean isSentenceEnd() {
            return isSentenceEnd;
        }

        public void setSentenceEnd(boolean sentenceEnd) {
            isSentenceEnd = sentenceEnd;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    /**
     * Text stream result for TTS
     */
    public static class TextSegment {
        private String text;
        private boolean isSentenceEnd;
        private boolean isEnd;

        public TextSegment() {
        }

        public TextSegment(String text, boolean isSentenceEnd, boolean isEnd) {
            this.text = text;
            this.isSentenceEnd = isSentenceEnd;
            this.isEnd = isEnd;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public boolean isSentenceEnd() {
            return isSentenceEnd;
        }

        public void setSentenceEnd(boolean sentenceEnd) {
            isSentenceEnd = sentenceEnd;
        }

        public boolean isEnd() {
            return isEnd;
        }

        public void setEnd(boolean end) {
            isEnd = end;
        }
    }

    /**
     * Convert text to speech (non-streaming)
     *
     * @param text Text to convert
     * @return Audio data
     */
    public abstract TTSAudio textToSpeech(String text);

    /**
     * Convert text to speech asynchronously (non-streaming)
     *
     * @param text Text to convert
     * @return TTSAudio result
     */
    public abstract reactor.core.publisher.Mono<TTSAudio> textToSpeechAsync(String text);

    /**
     * Convert text stream to speech audio stream (dual streaming)
     *
     * @param textStream Text segments from LLM stream
     * @return Flux of TTSAudio chunks
     */
    public abstract Flux<TTSAudio> textStreamToSpeechStream(Flux<TextSegment> textStream);

    /**
     * Get the provider name
     *
     * @return Provider name (e.g., "aliyun", "openai", "edge")
     */
    public abstract String getProviderName();

    /**
     * Get the interface type
     *
     * @return Interface type (NON_STREAM, DUAL_STREAM, HTTP_STREAM)
     */
    public abstract InterfaceType getInterfaceType();

    /**
     * Interface type enum
     */
    public enum InterfaceType {
        NON_STREAM,    // Simple TTS without streaming
        DUAL_STREAM,   // Streaming TTS with WebSocket
        HTTP_STREAM    // HTTP-based streaming
    }

    /**
     * Initialize the provider with configuration
     */
    public void initialize(TTSConfig config) {
        if (config.getApiKey() != null) {
            this.apiKey = config.getApiKey();
        }
        if (config.getBaseUrl() != null) {
            this.baseUrl = config.getBaseUrl();
        }
        if (config.getServiceUrl() != null) {
            this.serviceUrl = config.getServiceUrl();
        }
        if (config.getModel() != null) {
            this.model = config.getModel();
        }
        if (config.getVoice() != null) {
            this.voice = config.getVoice();
        }
        if (config.getRate() != null) {
            this.rate = config.getRate();
        }
        if (config.getPitch() != null) {
            this.pitch = config.getPitch();
        }
        if (config.getVolume() != null) {
            this.volume = config.getVolume();
        }
        log.info("{} TTS provider initialized: voice={}, rate={}, pitch={}, volume={}", 
                getProviderName(), voice, rate, pitch, volume);
    }

    // Getters for subclasses
    protected String getApiKey() {
        return apiKey;
    }

    protected String getBaseUrl() {
        return baseUrl;
    }

    protected String getServiceUrl() {
        return serviceUrl;
    }

    protected String getModel() {
        return model;
    }

    protected String getVoice() {
        return voice;
    }

    protected String getRate() {
        return rate;
    }

    protected String getPitch() {
        return pitch;
    }

    protected String getVolume() {
        return volume;
    }
}
