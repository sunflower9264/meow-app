package com.miaomiao.assistant.session.provider.asr.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.session.provider.asr.ASRProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Whisper ASR Provider
 * Uses OpenAI Whisper API for speech recognition
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "provider.asr.openai.enabled", havingValue = "true", matchIfMissing = false)
public class OpenAIWhisperASRProvider extends ASRProvider {

    private static final MediaType AUDIO_MP3 = MediaType.get("audio/mp3");
    private static final MediaType AUDIO_WAV = MediaType.get("audio/wav");
    private static final MediaType AUDIO_MPEG = MediaType.get("audio/mpeg");
    private static final MediaType AUDIO_MP4 = MediaType.get("audio/mp4");
    private static final MediaType AUDIO_M4A = MediaType.get("audio/x-m4a");
    private static final MediaType AUDIO_OGG = MediaType.get("audio/ogg");
    private static final MediaType AUDIO_WEBM = MediaType.get("audio/webm");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public OpenAIWhisperASRProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)  // Whisper can take time
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String speechToText(byte[] audioData, String format) {
        try {
            // Build multipart request
            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", getModel())
                    .addFormDataPart("file", "audio." + format,
                            RequestBody.create(audioData, getMediaType(format)));

            // Optional: specify language for better accuracy
            String lang = getLanguage();
            if (lang != null && !lang.isEmpty()) {
                bodyBuilder.addFormDataPart("language", lang);
            }

            Request request = new Request.Builder()
                    .url(getBaseUrl())
                    .addHeader("Authorization", "Bearer " + getApiKey())
                    .post(bodyBuilder.build())
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("OpenAI Whisper API error: {} - {}", response.code(), errorBody);
                    throw new IOException("Whisper API error: " + response.code());
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                String text = json.path("text").asText();
                log.debug("Whisper ASR result: {}", text);
                return text;
            }
        } catch (IOException e) {
            log.error("OpenAI Whisper ASR failed", e);
            throw new RuntimeException("ASR failed", e);
        }
    }

    @Override
    public CompletableFuture<String> speechToTextAsync(byte[] audioData, String format) {
        return CompletableFuture.supplyAsync(() -> speechToText(audioData, format));
    }

    @Override
    public String processAudioChunk(byte[] audioChunk, boolean isLast) {
        // Whisper doesn't support streaming, accumulate and process at end
        if (isLast) {
            return speechToText(audioChunk, "wav");
        }
        return null;
    }

    @Override
    public String startStreamingSession() {
        // Whisper doesn't support true streaming, return a session ID anyway
        return "whisper_" + System.currentTimeMillis();
    }

    @Override
    public String endStreamingSession(String sessionId) {
        // Not applicable for non-streaming Whisper
        return "";
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public boolean isStreamingSupported() {
        return false;  // Whisper API doesn't support streaming
    }

    private MediaType getMediaType(String format) {
        return switch (format.toLowerCase()) {
            case "mp3" -> AUDIO_MP3;
            case "wav" -> AUDIO_WAV;
            case "mpeg" -> AUDIO_MPEG;
            case "mp4" -> AUDIO_MP4;
            case "m4a" -> AUDIO_M4A;
            case "ogg" -> AUDIO_OGG;
            case "webm" -> AUDIO_WEBM;
            default -> AUDIO_WAV;
        };
    }
}
