package com.miaomiao.assistant.config;

import ai.z.openapi.ZhipuAiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI服务客户端配置
 * 统一管理各服务商的客户端单例
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AIClientConfig {

    private final AIServiceConfig aiServiceConfig;

    /**
     * 智谱AI客户端（全局单例）
     */
    @Bean
    public ZhipuAiClient zhipuAiClient() {
        AIServiceConfig.ProviderConfig config = getProviderConfig("zhipu");
        if (config == null) {
            log.warn("未找到智谱AI配置，跳过客户端初始化");
            return null;
        }

        log.info("初始化智谱AI客户端 (tokenCache={})", config.getEnableTokenCache());
        ZhipuAiClient.Builder builder = ZhipuAiClient.builder().apiKey(config.getApiKey());
        if (config.getEnableTokenCache()) {
            builder.enableTokenCache().tokenExpire(config.getTokenExpire());
        }
        return builder.build();
    }

    /**
     * 获取 Provider 配置（按名称前缀匹配）
     */
    private AIServiceConfig.ProviderConfig getProviderConfig(String providerPrefix) {
        return aiServiceConfig.getProviders().entrySet().stream()
                .filter(e -> e.getKey().contains(providerPrefix) && e.getValue().isEnabled())
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
