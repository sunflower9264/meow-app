package com.miaomiao.assistant.model.asr;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.asr.provider.ZhipuASRProvider;
import org.springframework.stereotype.Component;

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
     * 语音转文字（同步）
     */
    public ASRResult speechToText(String providerAndModelKey, byte[] audioData, ASROptions options) {
        BaseASRModelProvider provider = (BaseASRModelProvider) getProvider(providerAndModelKey);
        return provider.speechToText(audioData, options);
    }
}
