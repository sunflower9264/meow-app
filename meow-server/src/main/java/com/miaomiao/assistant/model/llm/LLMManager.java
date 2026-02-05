package com.miaomiao.assistant.model.llm;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.llm.provider.HttpLLMProvider;
import com.miaomiao.assistant.model.llm.provider.ZhipuLLMProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM服务管理器
 */
@Component
public class LLMManager extends AbstractModelManager {

    private final ZhipuAiClient zhipuAiClient;

    public LLMManager(AIServiceConfig config, ZhipuAiClient zhipuAiClient) {
        super(config);
        this.zhipuAiClient = zhipuAiClient;
    }

    @Override
    protected ModelType modelType() {
        return ModelType.LLM;
    }

    @Override
    protected BaseLLMProvider createProvider(String name) {
        AIServiceConfig.ProviderConfig providerConfig = config.getProviders().get(name);
        // 智谱AI Coding端点（使用HTTP方式）
        if (name.contains("zhipu-coding")) {
            return new HttpLLMProvider(name, providerConfig.getApiKey(), providerConfig.getBaseUrl());
        }
        // 智谱AI 通用端点（使用SDK）
        if (name.contains("zhipu") && zhipuAiClient != null) {
            return new ZhipuLLMProvider(name, zhipuAiClient);
        }
        return null;
    }

    /**
     * 流式对话
     */
    public Flux<AppLLMResponse> chatStream(String providerAndModelKey, List<AppChatMessage> messages, LLMOptions options) {
        BaseLLMProvider provider = (BaseLLMProvider) getProvider(providerAndModelKey);
        return provider.chatStream(messages, options);
    }
}
