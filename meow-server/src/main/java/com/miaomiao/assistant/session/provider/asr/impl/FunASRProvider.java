package com.miaomiao.assistant.session.provider.asr.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.session.provider.asr.ASRProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * FunASR (SenseVoice) ASR Provider
 * Uses local meow-service for free speech recognition
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "provider.asr.funasr.enabled", havingValue = "true", matchIfMissing = true)
public class FunASRProvider extends ASRProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public FunASRProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String speechToText(byte[] audioData, String format) {
        try {
            // Encode audio to base64
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // Build request body
            String jsonBody = objectMapper.writeValueAsString(new ASRRequest(audioBase64, format, getLanguage()));

            Request request = new Request.Builder()
                    .url(getBaseUrl() + "/asr")
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("FunASR API error: {} - {}", response.code(), errorBody);
                    throw new IOException("FunASR API error: " + response.code());
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                String text = json.path("text").asText();
                log.debug("FunASR result: {}", text);
                return text;
            }
        } catch (IOException e) {
            log.error("FunASR ASR failed", e);
            throw new RuntimeException("ASR failed", e);
        }
    }

    @Override
    public CompletableFuture<String> speechToTextAsync(byte[] audioData, String format) {
        return CompletableFuture.supplyAsync(() -> speechToText(audioData, format));
    }

    @Override
    public String processAudioChunk(byte[] audioChunk, boolean isLast) {
        // FunASR SenseVoice doesn't support streaming, accumulate and process at end
        if (isLast) {
            return speechToText(audioChunk, "pcm");
        }
        return null;
    }

    @Override
    public String startStreamingSession() {
        return "funasr_" + System.currentTimeMillis();
    }

    @Override
    public String endStreamingSession(String sessionId) {
        return "";
    }

    @Override
    public String getProviderName() {
        return "funasr";
    }

    @Override
    public boolean isStreamingSupported() {
        return false;  // SenseVoice doesn't support streaming
    }

    /**
     * ASR request body
     */
    private record ASRRequest(String audio_data, String format, String language) {}
}
