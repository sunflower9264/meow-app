package com.miaomiao.assistant.config;

import com.miaomiao.assistant.session.provider.asr.ASRProviderFactory;
import com.miaomiao.assistant.session.provider.llm.LLMProviderFactory;
import com.miaomiao.assistant.session.provider.tts.TTSProviderFactory;
import com.miaomiao.assistant.session.provider.vad.VADProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Provider initialization service
 * Initializes all providers with configuration from application.yml
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderInitializer {

    private final VADProviderFactory vadFactory;
    private final ASRProviderFactory asrFactory;
    private final LLMProviderFactory llmFactory;
    private final TTSProviderFactory ttsFactory;
    private final ProviderProperties properties;

    @PostConstruct
    public void initializeProviders() {
        log.info("Initializing providers...");

        // Initialize VAD providers
        vadFactory.initializeProviders(properties);
        vadFactory.setDefaultProvider(properties.getVad().getDefaultProvider());
        log.info("VAD providers initialized, default: {}", properties.getVad().getDefaultProvider());

        // Initialize ASR providers
        asrFactory.initializeProviders(properties);
        asrFactory.setDefaultProvider(properties.getAsr().getDefaultProvider());
        log.info("ASR providers initialized, default: {}", properties.getAsr().getDefaultProvider());

        // Initialize LLM providers
        llmFactory.initializeProviders(properties);
        llmFactory.setDefaultProvider(properties.getLlm().getDefaultProvider());
        log.info("LLM providers initialized, default: {}", properties.getLlm().getDefaultProvider());

        // Initialize TTS providers
        ttsFactory.initializeProviders(properties);
        ttsFactory.setDefaultProvider(properties.getTts().getDefaultProvider());
        log.info("TTS providers initialized, default: {}", properties.getTts().getDefaultProvider());

        log.info("All providers initialized successfully");
    }
}
