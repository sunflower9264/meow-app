package com.miaomiao.assistant.model;

import com.miaomiao.assistant.config.AIServiceConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class AbstractModelManager {

    private static final Logger log = LoggerFactory.getLogger(AbstractModelManager.class);

    public enum ModelType {
        ASR, LLM, TTS
    }

    protected final AIServiceConfig config;

    protected final ConcurrentMap<String, BaseModelProvider> modelProviderMap = new ConcurrentHashMap<>();

    protected AbstractModelManager(AIServiceConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        log.info("开始初始化{} Providers...", modelType());

        for (Map.Entry<String, AIServiceConfig.ProviderConfig> entry : config.getProviders().entrySet()) {
            String name = entry.getKey();
            AIServiceConfig.ProviderConfig providerConfig = entry.getValue();

            if (!providerConfig.isEnabled()) {
                log.info("跳过未启用的Provider: {}", name);
                continue;
            }

            // 根据类型获取对应的模型列表
            Set<String> models = getModels(providerConfig);

            if (models == null || models.isEmpty()) {
                log.debug("Provider {} 没有配置{}模型", name, modelType());
                continue;
            }

            try {
                BaseModelProvider provider = createProvider(name);
                if (provider == null) {
                    continue;
                }

                for (String model : models) {
                    String key = name + ":" + model;
                    modelProviderMap.put(key, provider);
                    log.info("注册{}模型: {} -> {}", modelType(), key, name);
                }
            } catch (Exception e) {
                log.warn("创建{} Provider失败: {}, 错误: {}", modelType(), name, e.getMessage());
            }
        }

        log.info("{} Providers初始化完成，支持{}个模型", modelType(), modelProviderMap.size());
    }

    /**
     * 模型类型
     */
    protected abstract ModelType modelType();

    protected abstract BaseModelProvider createProvider(String name);

    public BaseModelProvider getProvider(String providerAndModelKey) {
        return modelProviderMap.get(providerAndModelKey);
    }

    private Set<String> getModels(AIServiceConfig.ProviderConfig providerConfig) {
        return switch (modelType()) {
            case ASR -> providerConfig.getAsrModels();
            case LLM -> providerConfig.getLlmModels();
            case TTS -> providerConfig.getTtsModels();
        };
    }
}
