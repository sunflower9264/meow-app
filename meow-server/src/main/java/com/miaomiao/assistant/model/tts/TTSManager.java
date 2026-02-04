package com.miaomiao.assistant.model.tts;

import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.tts.provider.ZhipuTTSProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * TTS服务管理器
 */
@Component
public class TTSManager extends AbstractModelManager {

    private static final Logger log = LoggerFactory.getLogger(TTSManager.class);

    public TTSManager(AIServiceConfig config) {
        super(config);
    }

    @Override
    protected ModelType modelType() {
        return ModelType.TTS;
    }

    @Override
    protected BaseTTSProvider createProvider(String name, AIServiceConfig.ProviderConfig providerConfig) {
        if (name.contains("zhipu")) {
            return new ZhipuTTSProvider(name, providerConfig);
        }
        log.debug("Provider {} 不支持TTS服务", name);
        return null;
    }

    /**
     * 文本转语音（流式）
     */
    public Flux<TTSAudio> textToSpeechStream(String providerAndModelKey, String text, TTSOptions options) {
        BaseTTSProvider provider = (BaseTTSProvider) getProvider(providerAndModelKey);
        return provider.textToSpeechStream(text, options);
    }
}
