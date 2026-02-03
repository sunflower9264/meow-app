package com.miaomiao.assistant.session.provider.llm;

import com.miaomiao.assistant.config.ProviderProperties;
import com.miaomiao.assistant.session.provider.config.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM Provider Factory
 * Creates and manages LLM provider instances
 */
@Slf4j
@Component
public class LLMProviderFactory {

    private final java.util.Map<String, LLMProvider> providers = new java.util.concurrent.ConcurrentHashMap<>();
    private LLMProvider defaultProvider;

    @Autowired(required = false)
    private List<LLMProvider> providerList;

    public void registerProvider(String name, LLMProvider provider) {
        providers.put(name, provider);
        log.info("Registered LLM provider: {}", name);
    }

    public LLMProvider getProvider(String name) {
        LLMProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("LLM provider not found: " + name);
        }
        return provider;
    }

    public LLMProvider getDefaultProvider() {
        if (defaultProvider != null) {
            return defaultProvider;
        }
        if (!providers.isEmpty()) {
            return providers.values().iterator().next();
        }
        throw new IllegalStateException("No LLM provider available");
    }

    public void setDefaultProvider(String name) {
        LLMProvider provider = getProvider(name);
        this.defaultProvider = provider;
        log.info("Set default LLM provider: {}", name);
    }

    public void initializeProviders(ProviderProperties properties) {
        if (providerList != null) {
            for (LLMProvider provider : providerList) {
                String name = provider.getProviderName();
                LLMConfig config = convertConfig(properties, name);
                provider.initialize(config);
                registerProvider(name, provider);
            }
        }
    }

    private LLMConfig convertConfig(ProviderProperties properties, String providerName) {
        LLMConfig config = new LLMConfig();
        switch (providerName) {
            case "openai" -> {
                var c = properties.getLlm().getOpenai();
                config.setEnabled(c.isEnabled());
                config.setApiKey(c.getApiKey());
                config.setBaseUrl(c.getBaseUrl());
                config.setModel(c.getModel());
                config.setTemperature(c.getTemperature());
                config.setMaxTokens(c.getMaxTokens());
            }
            case "aliyun" -> {
                var c = properties.getLlm().getAliyun();
                config.setEnabled(c.isEnabled());
                config.setApiKey(c.getApiKey());
                config.setModel(c.getModel());
            }
            case "ollama" -> {
                var c = properties.getLlm().getOllama();
                config.setEnabled(c.isEnabled());
                config.setBaseUrl(c.getBaseUrl());
                config.setModel(c.getModel());
            }
            case "chatglm" -> {
                var c = properties.getLlm().getChatglm();
                config.setEnabled(c.isEnabled());
                config.setApiKey(c.getApiKey());
                config.setBaseUrl(c.getBaseUrl());
                config.setModel(c.getModel());
                config.setTemperature(c.getTemperature());
                config.setMaxTokens(c.getMaxTokens());
            }
        }
        return config;
    }
}
