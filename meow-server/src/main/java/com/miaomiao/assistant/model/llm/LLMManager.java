package com.miaomiao.assistant.model.llm;

import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.llm.provider.HttpApiProvider;
import com.miaomiao.assistant.model.llm.provider.ZhipuSDKProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM服务管理器
 */
@Component
public class LLMManager extends AbstractModelManager {

    private static final Logger log = LoggerFactory.getLogger(LLMManager.class);

    public LLMManager(AIServiceConfig config) {
        super(config);
    }

    @Override
    protected ModelType modelType() {
        return ModelType.LLM;
    }

    @Override
    protected BaseLLMProvider createProvider(String name, AIServiceConfig.ProviderConfig providerConfig) {
        // 智谱AI Coding端点
        if (name.contains("zhipu-coding")) {
            return new HttpApiProvider(name, providerConfig, providerConfig.getBaseUrl());
        }
        // 智谱AI 通用端点
        if (name.contains("zhipu")) {
            return new ZhipuSDKProvider(name, providerConfig);
        }
        log.debug("Provider {} 不支持LLM服务", name);
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
