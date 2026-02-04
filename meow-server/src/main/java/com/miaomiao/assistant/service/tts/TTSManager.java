package com.miaomiao.assistant.service.tts;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.service.tts.provider.ZhipuTTSProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTS服务管理器
 * 根据模型名称自动选择对应的Provider
 */
@Slf4j
@Component
public class TTSManager {

    private final AIServiceConfig config;
    private final Map<String, BaseTTSProvider> providers = new ConcurrentHashMap<>();
    // 模型名称 -> Provider 的映射
    private final Map<String, BaseTTSProvider> modelProviderMap = new ConcurrentHashMap<>();

    public TTSManager(AIServiceConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        log.info("开始初始化TTS Providers...");

        for (Map.Entry<String, AIServiceConfig.ProviderCredential> entry : config.getProviders().entrySet()) {
            String name = entry.getKey();
            AIServiceConfig.ProviderCredential credential = entry.getValue();

            if (!credential.isEnabled()) {
                log.info("跳过未启用的Provider: {}", name);
                continue;
            }

            try {
                BaseTTSProvider provider = createProvider(name, credential);
                if (provider != null) {
                    providers.put(name, provider);
                    // 注册该provider支持的所有模型
                    for (String model : provider.getSupportedModels()) {
                        modelProviderMap.put(model, provider);
                        log.info("注册TTS模型: {} -> {}", model, name);
                    }
                }
            } catch (Exception e) {
                log.warn("创建TTS Provider失败: {}, 错误: {}", name, e.getMessage());
            }
        }

        log.info("TTS Providers初始化完成，共{}个Provider，支持{}个模型", 
                providers.size(), modelProviderMap.size());
    }

    private BaseTTSProvider createProvider(String name, AIServiceConfig.ProviderCredential credential) {
        // 根据名称判断服务商类型
        if (name.contains("zhipu")) {
            ZhipuAiClient client = ZhipuAiClient.builder()
                    .apiKey(credential.getApiKey())
                    .baseUrl(credential.getBaseUrl())
                    .enableTokenCache()
                    .tokenExpire(credential.getTokenExpire())
                    .build();
            return new ZhipuTTSProvider(name, client);
        }

        // 可扩展其他TTS服务商...
        log.debug("Provider {} 不支持TTS服务", name);
        return null;
    }

    /**
     * 文本转语音（非流式）
     *
     * @param providerName Provider名称
     * @param text         文本
     * @param options      TTS选项（包含模型名称）
     */
    public BaseTTSProvider.TTSAudio textToSpeech(String providerName, String text, BaseTTSProvider.TTSOptions options) {
        BaseTTSProvider provider = getProvider(providerName);
        return provider.textToSpeech(text, options);
    }

    /**
     * 文本转语音（流式）
     *
     * @param providerName Provider名称
     * @param text         文本
     * @param options      TTS选项（包含模型名称）
     */
    public Flux<BaseTTSProvider.TTSAudio> textToSpeechStream(String providerName, String text, BaseTTSProvider.TTSOptions options) {
        BaseTTSProvider provider = getProvider(providerName);
        return provider.textToSpeechStream(text, options);
    }

    /**
     * 根据名称获取Provider
     */
    public BaseTTSProvider getProvider(String name) {
        BaseTTSProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未找到TTS Provider: " + name);
        }
        return provider;
    }

    /**
     * 检查Provider是否存在
     */
    public boolean hasProvider(String name) {
        return providers.containsKey(name);
    }

    /**
     * 检查是否支持指定模型
     */
    public boolean supportsModel(String model) {
        return modelProviderMap.containsKey(model);
    }

    /**
     * 获取所有支持的TTS模型
     */
    public String[] getSupportedModels() {
        return modelProviderMap.keySet().toArray(new String[0]);
    }
}
