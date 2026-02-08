package com.miaomiao.assistant.model.asr;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.asr.provider.ZhipuASRProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.TreeSet;

/**
 * ASR服务管理器
 */
@Component
public class ASRManager extends AbstractModelManager {

    private final ZhipuAiClient zhipuAiClient;

    public ASRManager(AIServiceConfig config, ZhipuAiClient zhipuAiClient) {
        super(config);
        this.zhipuAiClient = zhipuAiClient;
    }

    @Override
    protected ModelType modelType() {
        return ModelType.ASR;
    }

    @Override
    protected BaseASRModelProvider createProvider(String name) {
        if (name.contains("zhipu") && zhipuAiClient != null) {
            return new ZhipuASRProvider(name, zhipuAiClient);
        }
        return null;
    }

    /**
     * 语音转文字（流式）
     */
    public Flux<ASRResult> speechToTextStream(String providerAndModelKey, Flux<byte[]> audioStream, ASROptions options) {
        BaseASRModelProvider provider = getProviderOrThrow(providerAndModelKey);
        return provider.speechToTextStream(audioStream, options);
    }

    private BaseASRModelProvider getProviderOrThrow(String providerAndModelKey) {
        BaseASRModelProvider provider = (BaseASRModelProvider) getProvider(providerAndModelKey);
        if (provider != null) {
            return provider;
        }

        Set<String> supported = new TreeSet<>(modelProviderMap.keySet());
        throw new IllegalArgumentException("未找到ASR模型Provider: " + providerAndModelKey
                + "，已注册模型: " + supported);
    }
}
