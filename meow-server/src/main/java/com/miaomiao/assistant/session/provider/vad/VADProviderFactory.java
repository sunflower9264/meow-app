package com.miaomiao.assistant.session.provider.vad;

import com.miaomiao.assistant.config.ProviderProperties;
import com.miaomiao.assistant.session.provider.config.VADConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VAD Provider Factory
 */
@Slf4j
@Component
public class VADProviderFactory {

    private final java.util.Map<String, VADProvider> providers = new ConcurrentHashMap<>();
    private VADProvider defaultProvider;

    @Autowired(required = false)
    private List<VADProvider> providerList;

    public void registerProvider(String name, VADProvider provider) {
        providers.put(name, provider);
        log.info("Registered VAD provider: {}", name);
    }

    public VADProvider getProvider(String name) {
        VADProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("VAD provider not found: " + name);
        }
        return provider;
    }

    public VADProvider getDefaultProvider() {
        if (defaultProvider != null) {
            return defaultProvider;
        }
        if (!providers.isEmpty()) {
            return providers.values().iterator().next();
        }
        throw new IllegalStateException("No VAD provider available");
    }

    public void setDefaultProvider(String name) {
        VADProvider provider = getProvider(name);
        this.defaultProvider = provider;
        log.info("Set default VAD provider: {}", name);
    }

    public void initializeProviders(ProviderProperties properties) {
        if (providerList != null) {
            for (VADProvider provider : providerList) {
                String name = provider.getProviderName();
                VADConfig config = convertConfig(properties, name);
                provider.initialize(config);
                registerProvider(name, provider);
            }
        }
    }

    private VADConfig convertConfig(ProviderProperties properties, String providerName) {
        VADConfig config = new VADConfig();
        switch (providerName) {
            case "silero" -> {
                var c = properties.getVad().getSilero();
                config.setEnabled(c.isEnabled());
                config.setServiceUrl(c.getServiceUrl());
                if (c.getThreshold() != null) {
                    config.setThreshold(c.getThreshold());
                }
                if (c.getThresholdLow() != null) {
                    config.setThresholdLow(c.getThresholdLow());
                }
            }
        }
        return config;
    }
}
