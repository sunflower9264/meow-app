package com.miaomiao.assistant.model.llm.provider;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.llm.AppChatMessage;
import com.miaomiao.assistant.model.llm.AppLLMResponse;
import com.miaomiao.assistant.model.llm.BaseLLMProvider;
import com.miaomiao.assistant.model.llm.LLMOptions;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 智谱AI SDK Provider（通用端点） 使用: https://open.bigmodel.cn/api/paas/v4/
 */
@Slf4j
public class ZhipuSDKProvider extends BaseLLMProvider {

    private final ZhipuAiClient client;

    public ZhipuSDKProvider(String providerName, AIServiceConfig.ProviderConfig providerConfig) {
        super.providerName = providerName;
        super.apiKey = providerConfig.getApiKey();
        ZhipuAiClient.Builder builder = ZhipuAiClient.builder().apiKey(providerConfig.getApiKey());
        if (providerConfig.getEnableTokenCache()) {
            builder.enableTokenCache().tokenExpire(providerConfig.getTokenExpire());
        }
        this.client = builder.build();
        log.info("初始化智谱SDK Provider: name={}", providerName);
    }

    @Override
    public String chat(List<AppChatMessage> messages, LLMOptions options) {
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
    public Flux<AppLLMResponse> chatStream(List<AppChatMessage> messages, LLMOptions options) {
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

    private ChatCompletionCreateParams buildRequest(List<AppChatMessage> messages,
            LLMOptions options,
            boolean stream) {
        List<ChatMessage> sdkMessages = messages.stream()
                .map(msg -> ChatMessage.builder()
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

    private AppLLMResponse convertToResponse(ModelData modelData) {
        String delta = modelData.getDelta();
        boolean isFinished = modelData.getChoices() != null
                && !modelData.getChoices().isEmpty()
                && "stop".equals(modelData.getChoices().get(0).getFinishReason());
        return new AppLLMResponse(delta != null ? delta : "", isFinished);
    }
}
