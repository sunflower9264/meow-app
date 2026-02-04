package com.miaomiao.assistant.model.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.llm.AppChatMessage;
import com.miaomiao.assistant.model.llm.AppLLMResponse;
import com.miaomiao.assistant.model.llm.BaseLLMProvider;
import com.miaomiao.assistant.model.llm.LLMOptions;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通用HTTP API Provider 支持OpenAI格式的API（智谱Coding端点等）
 */
@Slf4j
public class HttpApiProvider extends BaseLLMProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public HttpApiProvider(String providerName, AIServiceConfig.ProviderConfig providerConfig, String baseUrl) {
        super.providerName = providerName;
        super.apiKey = providerConfig.getApiKey();
        super.baseUrl = baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("初始化HTTP API Provider: name={}", providerName);
    }

    @Override
    public String chat(List<AppChatMessage> messages, LLMOptions options) {
        try {
            Request request = buildHttpRequest(messages, options, false);

            try (Response response = client.newCall(request).execute()) {
                handleErrorResponse(response);
                String responseBody = response.body().string();
                JsonNode jsonBody = objectMapper.readTree(responseBody);
                return jsonBody.path("choices").get(0).path("message").path("content").asText();
            }
        } catch (IOException e) {
            log.error("HTTP API请求失败", e);
            throw new RuntimeException("LLM请求失败", e);
        }
    }

    @Override
    public Flux<AppLLMResponse> chatStream(List<AppChatMessage> messages, LLMOptions options) {
        Sinks.Many<AppLLMResponse> sink = Sinks.many().multicast().onBackpressureBuffer();

        try {
            Request request = buildHttpRequest(messages, options, true);
            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, createEventSourceListener(sink));
        } catch (Exception e) {
            log.error("HTTP API流式请求失败", e);
            sink.tryEmitError(e);
        }

        return sink.asFlux();
    }

    private Request buildHttpRequest(List<AppChatMessage> messages, LLMOptions options, boolean stream) {
        RequestBody body = buildRequestBody(messages, options, stream);
        return new Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    private RequestBody buildRequestBody(List<AppChatMessage> messages, LLMOptions options, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", options.getModel());
            root.put("stream", stream);

            if (options.getTemperature() != null) {
                root.put("temperature", options.getTemperature());
            }

            if (options.getMaxTokens() != null) {
                root.put("max_tokens", options.getMaxTokens());
            }

            ArrayNode messagesArray = root.putArray("messages");
            for (AppChatMessage message : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", message.role());
                msgNode.put("content", message.content());
            }

            return RequestBody.create(objectMapper.writeValueAsString(root), JSON);
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    /**
     * 处理错误响应
     */
    private void handleErrorResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "No body";
            log.error("HTTP API请求失败: {} - {}", response.code(), errorBody);
            throw new IOException("LLM请求失败: " + response.code());
        }
    }

    /**
     * 创建SSE事件监听器
     */
    private EventSourceListener createEventSourceListener(Sinks.Many<AppLLMResponse> sink) {
        return new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                log.debug("HTTP API SSE连接已打开");
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                try {
                    if ("[DONE]".equals(data)) {
                        sink.tryEmitNext(new AppLLMResponse("", true));
                        sink.tryEmitComplete();
                        return;
                    }

                    JsonNode jsonNode = objectMapper.readTree(data);
                    JsonNode choices = jsonNode.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        String content = delta.path("content").asText("");

                        if (!content.isEmpty()) {
                            sink.tryEmitNext(new AppLLMResponse(content, false));
                        }

                        String finishReason = choices.get(0).path("finish_reason").asText();
                        if ("stop".equals(finishReason)) {
                            sink.tryEmitNext(new AppLLMResponse("", true));
                            sink.tryEmitComplete();
                        }
                    }
                } catch (Exception e) {
                    log.error("解析SSE事件失败", e);
                    sink.tryEmitError(e);
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                log.debug("HTTP API SSE连接已关闭");
                sink.tryEmitComplete();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                log.error("HTTP API SSE连接失败", t);
                sink.tryEmitError(t);
            }
        };
    }
}
