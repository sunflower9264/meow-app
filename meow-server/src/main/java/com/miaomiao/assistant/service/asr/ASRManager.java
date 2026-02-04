package com.miaomiao.assistant.service.asr;

import ai.z.openapi.ZhipuAiClient;
import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.service.asr.provider.ZhipuASRProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ASR服务管理器
 * 根据模型名称自动选择对应的Provider
 */
@Slf4j
@Component
public class ASRManager {

    private final AIServiceConfig config;
    private final Map<String, BaseASRProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, BaseASRProvider> modelProviderMap = new ConcurrentHashMap<>();

    public ASRManager(AIServiceConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        log.info("开始初始化ASR Providers...");

        for (Map.Entry<String, AIServiceConfig.ProviderCredential> entry : config.getProviders().entrySet()) {
            String name = entry.getKey();
            AIServiceConfig.ProviderCredential credential = entry.getValue();

            if (!credential.isEnabled()) {
                log.info("跳过未启用的Provider: {}", name);
                continue;
            }

            try {
                BaseASRProvider provider = createProvider(name, credential);
                if (provider != null) {
                    providers.put(name, provider);
                    // 注册该provider支持的所有模型
                    for (String model : provider.getSupportedModels()) {
                        modelProviderMap.put(model, provider);
                        log.info("注册ASR模型: {} -> {}", model, name);
                    }
                }
            } catch (Exception e) {
                log.warn("创建ASR Provider失败: {}, 错误: {}", name, e.getMessage());
            }
        }

        log.info("ASR Providers初始化完成，共{}个Provider，支持{}个模型", providers.size(), modelProviderMap.size());
    }

    private BaseASRProvider createProvider(String name, AIServiceConfig.ProviderCredential credential) {
        // 根据名称判断服务商类型
        if (name.contains("zhipu")) {
            ZhipuAiClient client = ZhipuAiClient.builder()
                    .apiKey(credential.getApiKey())
                    .baseUrl(credential.getBaseUrl())
                    .enableTokenCache()
                    .tokenExpire(credential.getTokenExpire())
                    .build();
            return new ZhipuASRProvider(name, client);
        }

        // 可扩展其他ASR服务商...
        log.debug("Provider {} 不支持ASR服务", name);
        return null;
    }

    /**
     * 语音转文字（同步）
     *
     * @param providerName Provider名称
     * @param audioData    音频数据
     * @param options      ASR选项（包含模型名称）
     */
    public BaseASRProvider.ASRResult speechToText(String providerName, byte[] audioData, BaseASRProvider.ASROptions options) {
        BaseASRProvider provider = getProvider(providerName);
        return provider.speechToText(audioData, options);
    }

    /**
     * 语音转文字（异步）
     *
     * @param providerName Provider名称
     * @param audioData    音频数据
     * @param options      ASR选项（包含模型名称）
     */
    public CompletableFuture<BaseASRProvider.ASRResult> speechToTextAsync(String providerName, byte[] audioData, BaseASRProvider.ASROptions options) {
        BaseASRProvider provider = getProvider(providerName);
        return provider.speechToTextAsync(audioData, options);
    }

    /**
     * 语音转文字（流式）
     *
     * @param providerName Provider名称
     * @param audioStream  音频数据流
     * @param options      ASR选项（包含模型名称）
     */
    public Flux<BaseASRProvider.ASRResult> speechToTextStream(String providerName, Flux<byte[]> audioStream, BaseASRProvider.ASROptions options) {
        BaseASRProvider provider = getProvider(providerName);
        return provider.speechToTextStream(audioStream, options);
    }

    /**
     * 根据名称获取Provider
     */
    public BaseASRProvider getProvider(String name) {
        BaseASRProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未找到ASR Provider: " + name);
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
     * 获取所有支持的ASR模型
     */
    public String[] getSupportedModels() {
        return modelProviderMap.keySet().toArray(new String[0]);
    }
}
