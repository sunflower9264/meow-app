package com.miaomiao.assistant.model.tts;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.model.AbstractModelManager;
import com.miaomiao.assistant.model.tts.provider.ZhipuTTSProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * TTS服务管理器
 */
@Component
public class TTSManager extends AbstractModelManager {

    private final ZhipuAiClient zhipuAiClient;

    public TTSManager(AIServiceConfig config, ZhipuAiClient zhipuAiClient) {
        super(config);
        this.zhipuAiClient = zhipuAiClient;
    }

    @Override
    protected ModelType modelType() {
        return ModelType.TTS;
    }

    @Override
    protected BaseTTSProvider createProvider(String name) {
        if (name.contains("zhipu") && zhipuAiClient != null) {
            return new ZhipuTTSProvider(name, zhipuAiClient);
        }
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
