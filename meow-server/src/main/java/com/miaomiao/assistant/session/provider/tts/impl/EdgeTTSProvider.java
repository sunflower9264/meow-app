package com.miaomiao.assistant.session.provider.tts.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.session.provider.tts.TTSProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

/**
 * Edge TTS Provider
 * Uses Microsoft Edge TTS (free, no API key required)
 * Uses edge-tts library via Python service or direct HTTP calls
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "provider.tts.edge.enabled", havingValue = "true", matchIfMissing = false)
public class EdgeTTSProvider extends TTSProvider {

    private static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";
    private static final String DEFAULT_SERVICE_URL = "http://127.0.0.1:8765";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public EdgeTTSProvider() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        // Set default values (may be overridden by initialize())
        this.voice = DEFAULT_VOICE;
        this.serviceUrl = DEFAULT_SERVICE_URL;
    }

    @Override
    public TTSAudio textToSpeech(String text) {
        try {
            // Call Python Edge TTS service
            JsonNode requestBody = objectMapper.createObjectNode()
                    .put("text", text)
                    .put("voice", getVoice())
                    .put("rate", getRate())
                    .put("pitch", getPitch())
                    .put("volume", getVolume());

            Request request = new Request.Builder()
                    .url(getServiceUrl() + "/tts")
                    .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Edge TTS service error: {}", response.code());
                    // Return empty audio
                    return new TTSAudio(new byte[0], "mp3", true);
                }

                JsonNode json = objectMapper.readTree(response.body().string());
                String audioBase64 = json.path("audio_data").asText();
                byte[] audioData = java.util.Base64.getDecoder().decode(audioBase64);

                TTSAudio result = new TTSAudio();
                result.setAudioData(audioData);
                result.setFormat("mp3");
                result.setFinished(true);
                result.setText(text);
                return result;
            }
        } catch (Exception e) {
            log.error("Edge TTS failed", e);
            return new TTSAudio(new byte[0], "mp3", true);
        }
    }

    @Override
    public reactor.core.publisher.Mono<TTSAudio> textToSpeechAsync(String text) {
        return reactor.core.publisher.Mono.fromSupplier(() -> textToSpeech(text));
    }

    @Override
    public Flux<TTSAudio> textStreamToSpeechStream(Flux<TextSegment> textStream) {
        // Use concatMap to ensure sequential processing
        // First sentence splits early (at comma) for low latency
        return textStream.concatMap(segment -> {
            log.debug("Edge TTS streaming: {}", segment.getText());

            // Call streaming TTS service with TRUE streaming response
            return Flux.create(sink -> {
                try {
                    JsonNode requestBody = objectMapper.createObjectNode()
                            .put("text", segment.getText())
                            .put("voice", getVoice())
                            .put("rate", getRate())
                            .put("pitch", getPitch())
                            .put("volume", getVolume());

                    Request request = new Request.Builder()
                            .url(getServiceUrl() + "/tts/stream")
                            .post(RequestBody.create(requestBody.toString(), MediaType.get("application/json")))
                            .build();

                    // Use streaming response - read chunks as they arrive
                    log.info("Calling TTS service: {} for text: {}", getServiceUrl() + "/tts/stream", segment.getText());
                    client.newCall(request).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, java.io.IOException e) {
                            log.error("Edge TTS stream request failed - URL: {}, Error: {}", 
                                    getServiceUrl() + "/tts/stream", e.getMessage(), e);
                            sink.error(new RuntimeException("TTS服务连接失败: " + e.getMessage(), e));
                        }

                        @Override
                        public void onResponse(Call call, Response response) {
                            try {
                                if (!response.isSuccessful()) {
                                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                                    log.error("Edge TTS stream service returned error - Code: {}, Body: {}", 
                                            response.code(), errorBody);
                                    sink.error(new RuntimeException("TTS服务返回错误 " + response.code() + ": " + errorBody));
                                    response.close();
                                    return;
                                }

                                if (response.body() == null) {
                                    sink.complete();
                                    return;
                                }

                                // Read streaming response in chunks (true streaming)
                                java.io.InputStream inputStream = response.body().byteStream();
                                byte[] buffer = new byte[4096];  // 4KB chunks for real-time streaming
                                int bytesRead;
                                int chunkIndex = 0;
                                int totalBytes = 0;

                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    byte[] chunk = new byte[bytesRead];
                                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                                    totalBytes += bytesRead;

                                    TTSAudio ttsAudio = new TTSAudio();
                                    ttsAudio.setAudioData(chunk);
                                    ttsAudio.setFormat("mp3");
                                    ttsAudio.setFinished(false);  // Will be set true on last chunk
                                    ttsAudio.setText(segment.getText());
                                    ttsAudio.setSentenceEnd(false);

                                    sink.next(ttsAudio);
                                    chunkIndex++;
                                }

                                // Send final marker
                                TTSAudio finalAudio = new TTSAudio();
                                finalAudio.setAudioData(new byte[0]);
                                finalAudio.setFormat("mp3");
                                finalAudio.setFinished(true);
                                finalAudio.setText(segment.getText());
                                finalAudio.setSentenceEnd(segment.isSentenceEnd());
                                sink.next(finalAudio);

                                log.debug("Edge TTS streamed {} chunks ({} bytes) for: {}", 
                                        chunkIndex, totalBytes, segment.getText());
                                
                                inputStream.close();
                                response.close();
                                sink.complete();
                            } catch (Exception e) {
                                log.error("Edge TTS streaming read failed", e);
                                sink.error(e);
                            }
                        }
                    });
                } catch (Exception e) {
                    log.error("Edge TTS streaming failed", e);
                    sink.error(e);
                }
            });
        });
    }

    @Override
    public String getProviderName() {
        return "edge";
    }

    @Override
    public InterfaceType getInterfaceType() {
        return InterfaceType.HTTP_STREAM;
    }
}
