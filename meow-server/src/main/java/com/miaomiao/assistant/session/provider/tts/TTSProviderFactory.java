package com.miaomiao.assistant.session.provider.tts;

import com.miaomiao.assistant.config.ProviderProperties;
import com.miaomiao.assistant.session.provider.config.TTSConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TTS Provider Factory
 * Creates and manages TTS provider instances
 */
@Slf4j
@Component
public class TTSProviderFactory {

    private final java.util.Map<String, TTSProvider> providers = new java.util.concurrent.ConcurrentHashMap<>();
    private TTSProvider defaultProvider;

    @Autowired(required = false)
    private List<TTSProvider> providerList;

    public void registerProvider(String name, TTSProvider provider) {
        providers.put(name, provider);
        log.info("Registered TTS provider: {}", name);
    }

    public TTSProvider getProvider(String name) {
        TTSProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("TTS provider not found: " + name);
        }
        return provider;
    }

    public TTSProvider getDefaultProvider() {
        if (defaultProvider != null) {
            return defaultProvider;
        }
        if (!providers.isEmpty()) {
            return providers.values().iterator().next();
        }
        throw new IllegalStateException("No TTS provider available");
    }

    public void setDefaultProvider(String name) {
        TTSProvider provider = getProvider(name);
        this.defaultProvider = provider;
        log.info("Set default TTS provider: {}", name);
    }

    public void initializeProviders(ProviderProperties properties) {
        if (providerList != null) {
            for (TTSProvider provider : providerList) {
                String name = provider.getProviderName();
                TTSConfig config = convertConfig(properties, name);
                provider.initialize(config);
                registerProvider(name, provider);
            }
        }
    }

    private TTSConfig convertConfig(ProviderProperties properties, String providerName) {
        TTSConfig config = new TTSConfig();
        switch (providerName) {
            case "edge" -> {
                var c = properties.getTts().getEdge();
                config.setEnabled(c.isEnabled());
                config.setServiceUrl(c.getServiceUrl());
                config.setVoice(c.getVoice());
                config.setRate(c.getRate());
                config.setPitch(c.getPitch());
                config.setVolume(c.getVolume());
            }
            case "aliyun" -> {
                var c = properties.getTts().getAliyun();
                config.setEnabled(c.isEnabled());
                config.setAppKey(c.getAppKey());
                config.setAccessKeyId(c.getAccessKeyId());
                config.setAccessKeySecret(c.getAccessKeySecret());
                config.setVoice(c.getVoice());
            }
            case "openai" -> {
                var c = properties.getTts().getOpenai();
                config.setEnabled(c.isEnabled());
                config.setApiKey(c.getApiKey());
                config.setModel(c.getModel());
                config.setVoice(c.getVoice());
            }
        }
        return config;
    }
}
