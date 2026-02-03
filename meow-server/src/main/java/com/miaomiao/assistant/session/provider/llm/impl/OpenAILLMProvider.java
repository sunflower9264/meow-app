package com.miaomiao.assistant.session.provider.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaomiao.assistant.session.provider.llm.LLMProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI LLM Provider Implementation
 * Supports streaming and non-streaming responses
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "provider.llm.openai.enabled", havingValue = "true", matchIfMissing = false)
public class OpenAILLMProvider extends LLMProvider {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public OpenAILLMProvider() {
        this.baseUrl = OPENAI_API_URL;
        this.model = "gpt-3.5-turbo";
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // 5 minutes for streaming
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String response(List<ChatMessage> messages, Double temperature, Integer maxTokens) {
        try {
            RequestBody body = buildRequestBody(messages, temperature, maxTokens, false);
            Request request = new Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }

                JsonNode jsonBody = objectMapper.readTree(response.body().string());
                return jsonBody.path("choices").get(0).path("message").path("content").asText();
            }
        } catch (IOException e) {
            log.error("OpenAI LLM request failed", e);
            throw new RuntimeException("LLM request failed", e);
        }
    }

    @Override
    public Flux<LLMResponse> responseStream(List<ChatMessage> messages, Double temperature, Integer maxTokens) {
        Sinks.Many<LLMResponse> sink = Sinks.many().multicast().onBackpressureBuffer();

        try {
            RequestBody body = buildRequestBody(messages, temperature, maxTokens, true);
            Request request = new Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, new EventSourceListener() {
                private final StringBuilder contentBuilder = new StringBuilder();

                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    log.debug("OpenAI SSE connection opened");
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    try {
                        if ("[DONE]".equals(data)) {
                            // Stream finished
                            sink.tryEmitNext(new LLMResponse("", true));
                            sink.tryEmitComplete();
                            return;
                        }

                        JsonNode jsonNode = objectMapper.readTree(data);
                        JsonNode choices = jsonNode.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).path("delta");
                            String content = delta.path("content").asText();

                            if (!content.isEmpty()) {
                                contentBuilder.append(content);
                                sink.tryEmitNext(new LLMResponse(content, false));
                            }

                            // Check if finished
                            String finishReason = choices.get(0).path("finish_reason").asText();
                            if ("stop".equals(finishReason)) {
                                sink.tryEmitNext(new LLMResponse("", true));
                                sink.tryEmitComplete();
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error parsing SSE event", e);
                        sink.tryEmitError(e);
                    }
                }

                @Override
                public void onClosed(EventSource eventSource) {
                    log.debug("OpenAI SSE connection closed");
                    sink.tryEmitComplete();
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    log.error("OpenAI SSE connection failed", t);
                    sink.tryEmitError(t);
                }
            });

        } catch (Exception e) {
            log.error("Failed to create OpenAI stream", e);
            sink.tryEmitError(e);
        }

        return sink.asFlux();
    }

    private RequestBody buildRequestBody(List<ChatMessage> messages, Double temperature, Integer maxTokens, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("stream", stream);
            if (temperature != null) {
                root.put("temperature", temperature);
            }
            if (maxTokens != null) {
                root.put("max_tokens", maxTokens);
            }

            ArrayNode messagesArray = root.putArray("messages");
            for (ChatMessage message : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", message.getRole());
                msgNode.put("content", message.getContent());
                if (message.getName() != null) {
                    msgNode.put("name", message.getName());
                }
            }

            return RequestBody.create(objectMapper.writeValueAsString(root), JSON);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}
