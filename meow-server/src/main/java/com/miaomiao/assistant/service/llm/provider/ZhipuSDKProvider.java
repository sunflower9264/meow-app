package com.miaomiao.assistant.service.llm.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.miaomiao.assistant.service.llm.BaseLLMProvider;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智谱AI SDK Provider（通用端点）
 * 使用: https://open.bigmodel.cn/api/paas/v4/
 */
@Slf4j
public class ZhipuSDKProvider extends BaseLLMProvider {

    /**
     * 该Provider支持的模型列表
     */
    private static final Set<String> SUPPORTED_MODELS = Set.of(
            "glm-4", "glm-4-plus", "glm-4-air", "glm-4-airx", 
            "glm-4-flash", "glm-4-flashx", "glm-4-long",
            "glm-4.7"
    );

    private final ZhipuAiClient client;

    public ZhipuSDKProvider(String providerName, String apiKey, String baseUrl,
                           boolean enableTokenCache, int tokenExpire) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;

        ZhipuAiClient.Builder builder = ZhipuAiClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl);

        if (enableTokenCache) {
            builder.enableTokenCache().tokenExpire(tokenExpire);
        }

        this.client = builder.build();
        log.info("初始化智谱SDK Provider: name={}, 支持模型: {}", providerName, SUPPORTED_MODELS);
    }

    @Override
    public boolean supportsModel(String model) {
        return SUPPORTED_MODELS.contains(model);
    }

    @Override
    public Set<String> getSupportedModels() {
        return SUPPORTED_MODELS;
    }

    @Override
    public String chat(List<ChatMessage> messages, LLMOptions options) {
        try {
            ChatCompletionCreateParams request = buildRequest(messages, options, false);
            ChatCompletionResponse response = client.chat().createChatCompletion(request);

            if (!response.isSuccess()) {
                throw new RuntimeException("LLM请求失败: " + response.getMsg());
            }
            
            return response.getData().getChoices().get(0).getMessage().getContent().toString();
        } catch (Exception e) {
            log.error("智谱SDK非流式请求失败", e);
            throw new RuntimeException("LLM请求失败", e);
        }
    }

    @Override
    public Flux<LLMResponse> chatStream(List<ChatMessage> messages, LLMOptions options) {
        try {
            ChatCompletionCreateParams request = buildRequest(messages, options, true);
            ChatCompletionResponse response = client.chat().createChatCompletion(request);

            if (!response.isSuccess()) {
                return Flux.error(new RuntimeException("LLM流式请求失败: " + response.getMsg()));
            }

            Flowable<ModelData> flowable = response.getFlowable();
            if (flowable == null) {
                return Flux.error(new RuntimeException("流式响应为空"));
            }

            return Flux.from(flowable)
                    .map(this::convertToResponse)
                    .doOnError(e -> log.error("智谱SDK流式响应错误", e));
        } catch (Exception e) {
            log.error("智谱SDK流式请求失败", e);
            return Flux.error(e);
        }
    }

    private ChatCompletionCreateParams buildRequest(List<ChatMessage> messages, 
                                                    LLMOptions options, 
                                                    boolean stream) {
        List<ai.z.openapi.service.model.ChatMessage> sdkMessages = messages.stream()
                .map(msg -> ai.z.openapi.service.model.ChatMessage.builder()
                        .role(msg.role())
                        .content(msg.content())
                        .build())
                .collect(Collectors.toList());

        return ChatCompletionCreateParams.builder()
                .model(options.getModel())
                .messages(sdkMessages)
                .temperature(options.getTemperature() != null ? options.getTemperature().floatValue() : 0.7f)
                .maxTokens(options.getMaxTokens() != null ? options.getMaxTokens() : 2000)
                .stream(stream)
                .build();
    }

    private LLMResponse convertToResponse(ModelData modelData) {
        String delta = modelData.getDelta();
        boolean isFinished = modelData.getChoices() != null
                && !modelData.getChoices().isEmpty()
                && "stop".equals(modelData.getChoices().get(0).getFinishReason());
        return new LLMResponse(delta != null ? delta : "", isFinished);
    }
}
