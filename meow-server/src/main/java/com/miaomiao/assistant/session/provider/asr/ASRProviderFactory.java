package com.miaomiao.assistant.session.provider.asr;

import com.miaomiao.assistant.config.ProviderProperties;
import com.miaomiao.assistant.session.provider.config.ASRConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ASR Provider Factory
 * Creates and manages ASR provider instances
 */
@Slf4j
@Component
public class ASRProviderFactory {

    private final java.util.Map<String, ASRProvider> providers = new java.util.concurrent.ConcurrentHashMap<>();
    private ASRProvider defaultProvider;

    @Autowired(required = false)
    private List<ASRProvider> providerList;

    public void registerProvider(String name, ASRProvider provider) {
        providers.put(name, provider);
        log.info("Registered ASR provider: {}", name);
    }

    public ASRProvider getProvider(String name) {
        ASRProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("ASR provider not found: " + name);
        }
        return provider;
    }

    public ASRProvider getDefaultProvider() {
        if (defaultProvider != null) {
            return defaultProvider;
        }
        if (!providers.isEmpty()) {
            return providers.values().iterator().next();
        }
        throw new IllegalStateException("No ASR provider available");
    }

    public void setDefaultProvider(String name) {
        ASRProvider provider = getProvider(name);
        this.defaultProvider = provider;
        log.info("Set default ASR provider: {}", name);
    }

    public void initializeProviders(ProviderProperties properties) {
        if (providerList != null) {
            for (ASRProvider provider : providerList) {
                String name = provider.getProviderName();
                ASRConfig config = convertConfig(properties, name);
                provider.initialize(config);
                registerProvider(name, provider);
            }
        }
    }

    private ASRConfig convertConfig(ProviderProperties properties, String providerName) {
        ASRConfig config = new ASRConfig();
        switch (providerName) {
            case "funasr" -> {
                var c = properties.getAsr().getFunasr();
                config.setEnabled(c.isEnabled());
                config.setBaseUrl(c.getServiceUrl());
                config.setLanguage(c.getLanguage());
            }
            case "openai" -> {
                var c = properties.getAsr().getOpenai();
                config.setEnabled(c.isEnabled());
                config.setApiKey(c.getApiKey());
                config.setBaseUrl(c.getBaseUrl());
                config.setModel(c.getModel());
                config.setLanguage(c.getLanguage());
            }
            case "aliyun" -> {
                var c = properties.getAsr().getAliyun();
                config.setEnabled(c.isEnabled());
                config.setAppKey(c.getAppKey());
                config.setAccessKeyId(c.getAccessKeyId());
                config.setAccessKeySecret(c.getAccessKeySecret());
            }
        }
        return config;
    }
}
