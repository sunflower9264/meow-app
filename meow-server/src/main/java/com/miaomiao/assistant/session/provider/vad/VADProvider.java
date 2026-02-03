package com.miaomiao.assistant.session.provider.vad;

import com.miaomiao.assistant.session.provider.config.VADConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * VAD (Voice Activity Detection) Provider Abstract Base Class
 */
@Slf4j
public abstract class VADProvider {

    // Common configuration fields
    protected String serviceUrl = "http://127.0.0.1:8765";
    protected double threshold = 0.5;
    protected double thresholdLow = 0.15;

    /**
     * VAD result
     */
    public static class VADResult {
        private boolean hasVoice;
        private double probability;
        private boolean speechEnded;

        public VADResult(boolean hasVoice, double probability) {
            this.hasVoice = hasVoice;
            this.probability = probability;
        }

        public VADResult(boolean hasVoice, double probability, boolean speechEnded) {
            this.hasVoice = hasVoice;
            this.probability = probability;
            this.speechEnded = speechEnded;
        }

        public boolean hasVoice() {
            return hasVoice;
        }

        public double getProbability() {
            return probability;
        }

        public boolean isSpeechEnded() {
            return speechEnded;
        }
    }

    /**
     * Check if audio contains voice activity
     *
     * @param audioData PCM audio data (16-bit, 16kHz, mono)
     * @return VADResult with hasVoice and probability
     */
    public abstract VADResult detectVoice(byte[] audioData);

    /**
     * Start a streaming VAD session
     *
     * @param sessionId Unique session identifier
     */
    public abstract void startSession(String sessionId);

    /**
     * Process audio chunk in streaming session
     *
     * @param sessionId Session identifier
     * @param audioChunk Audio chunk data
     * @return VADResult with hasVoice, probability, and speechEnded flag
     */
    public abstract VADResult processStream(String sessionId, byte[] audioChunk);

    /**
     * End streaming session
     *
     * @param sessionId Session identifier
     * @return True if session had voice activity
     */
    public abstract boolean endSession(String sessionId);

    /**
     * Get provider name
     */
    public abstract String getProviderName();

    /**
     * Initialize with configuration
     */
    public void initialize(VADConfig config) {
        if (config.getServiceUrl() != null) {
            this.serviceUrl = config.getServiceUrl();
        }
        this.threshold = config.getThreshold();
        this.thresholdLow = config.getThresholdLow();
        log.info("{} VAD provider initialized: url={}, threshold={}, thresholdLow={}", 
                getProviderName(), serviceUrl, threshold, thresholdLow);
    }

    // Getters for subclasses
    protected String getServiceUrl() {
        return serviceUrl;
    }

    protected double getThreshold() {
        return threshold;
    }

    protected double getThresholdLow() {
        return thresholdLow;
    }
}
