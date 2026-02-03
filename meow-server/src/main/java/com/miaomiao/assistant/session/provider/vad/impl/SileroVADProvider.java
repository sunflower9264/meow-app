package com.miaomiao.assistant.session.provider.vad.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.session.provider.vad.VADProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Silero VAD Provider
 * Calls Python VAD service via HTTP API
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "provider.vad.silero.enabled", havingValue = "true", matchIfMissing = false)
public class SileroVADProvider extends VADProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public SileroVADProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public VADResult detectVoice(byte[] audioData) {
        try {
            // Build request
            JsonNode requestBody = objectMapper.createObjectNode()
                    .put("audio_data", Base64.getEncoder().encodeToString(audioData))
                    .put("threshold", getThreshold())
                    .put("threshold_low", getThresholdLow());

            Request request = new Request.Builder()
                    .url(getServiceUrl() + "/vad")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("VAD service error: {}", response.code());
                    return new VADResult(false, 0.0);
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                boolean hasVoice = json.path("has_voice").asBoolean();
                double probability = json.path("probability").asDouble();
                return new VADResult(hasVoice, probability);
            }
        } catch (Exception e) {
            log.error("VAD detection failed", e);
            return new VADResult(false, 0.0);
        }
    }

    @Override
    public void startSession(String sessionId) {
        try {
            HttpUrl url = HttpUrl.parse(getServiceUrl() + "/vad/session/start")
                    .newBuilder()
                    .addQueryParameter("session_id", sessionId)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create("", JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Failed to start VAD session: {}", response.code());
                }
            }
        } catch (Exception e) {
            log.error("Failed to start VAD session", e);
        }
    }

    @Override
    public VADResult processStream(String sessionId, byte[] audioChunk) {
        try {
            JsonNode requestBody = objectMapper.createObjectNode()
                    .put("audio_chunk", Base64.getEncoder().encodeToString(audioChunk))
                    .put("session_id", sessionId)
                    .put("threshold", getThreshold())
                    .put("threshold_low", getThresholdLow());

            Request request = new Request.Builder()
                    .url(getServiceUrl() + "/vad/session/process")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new VADResult(false, 0.0, false);
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                boolean hasVoice = json.path("has_voice").asBoolean();
                double probability = json.path("probability").asDouble();
                boolean speechEnded = json.path("speech_ended").asBoolean();
                return new VADResult(hasVoice, probability, speechEnded);
            }
        } catch (Exception e) {
            log.error("VAD stream processing failed", e);
            return new VADResult(false, 0.0, false);
        }
    }

    @Override
    public boolean endSession(String sessionId) {
        try {
            JsonNode requestBody = objectMapper.createObjectNode()
                    .put("session_id", sessionId);

            Request request = new Request.Builder()
                    .url(getServiceUrl() + "/vad/session/end")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    JsonNode json = objectMapper.readTree(response.body().string());
                    return json.path("had_voice").asBoolean();
                }
            }
        } catch (Exception e) {
            log.error("Failed to end VAD session", e);
        }
        return false;
    }

    @Override
    public String getProviderName() {
        return "silero";
    }
}
