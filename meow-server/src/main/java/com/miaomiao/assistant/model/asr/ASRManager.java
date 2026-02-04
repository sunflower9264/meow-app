package com.miaomiao.assistant.model.asr;

import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.asr.provider.ZhipuASRModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ASR服务管理器
 */
@Component
public class ASRManager extends AbstractModelManager {

    private static final Logger log = LoggerFactory.getLogger(ASRManager.class);

    public ASRManager(AIServiceConfig config) {
        super(config);
    }

    @Override
    protected ModelType modelType() {
        return ModelType.ASR;
    }

    @Override
    protected BaseASRModelProvider createProvider(String name, AIServiceConfig.ProviderConfig providerConfig) {
        if (name.contains("zhipu")) {
            return new ZhipuASRModelProvider(name, providerConfig);
        }
        log.debug("Provider {} 不支持ASR服务", name);
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
