package com.miaomiao.assistant.service.llm;

import com.miaomiao.assistant.config.AIServiceConfig;
import com.miaomiao.assistant.service.llm.provider.HttpApiProvider;
import com.miaomiao.assistant.service.llm.provider.ZhipuSDKProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM服务管理器
 * 根据模型名称自动选择对应的Provider
 */
@Slf4j
@Component
public class LLMManager {

    private final AIServiceConfig config;
    private final Map<String, BaseLLMProvider> providers = new ConcurrentHashMap<>();
    // 模型名称 -> Provider 的映射
    private final Map<String, BaseLLMProvider> modelProviderMap = new ConcurrentHashMap<>();

    public LLMManager(AIServiceConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        log.info("开始初始化LLM Providers...");

        for (Map.Entry<String, AIServiceConfig.ProviderCredential> entry : config.getProviders().entrySet()) {
            String name = entry.getKey();
            AIServiceConfig.ProviderCredential credential = entry.getValue();

            if (!credential.isEnabled()) {
                log.info("跳过未启用的Provider: {}", name);
                continue;
            }

            try {
                List<BaseLLMProvider> providerList = createProviders(name, credential);
                for (BaseLLMProvider provider : providerList) {
                    providers.put(provider.getProviderName(), provider);
                    // 注册该provider支持的所有模型
                    for (String model : provider.getSupportedModels()) {
                        modelProviderMap.put(model, provider);
                        log.info("注册LLM模型: {} -> {}", model, provider.getProviderName());
                    }
                }
            } catch (Exception e) {
                log.warn("创建LLM Provider失败: {}, 错误: {}", name, e.getMessage());
            }
        }

        log.info("LLM Providers初始化完成，共{}个Provider，支持{}个模型",
                providers.size(), modelProviderMap.size());
    }

    /**
     * 根据配置创建Providers
     * 一个服务商可能创建多个Provider（如智谱有通用端点和Coding端点）
     */
    private List<BaseLLMProvider> createProviders(String name, AIServiceConfig.ProviderCredential credential) {
        if (name.contains("zhipu")) {
            // 智谱AI - 创建通用端点和Coding端点两个Provider
            ZhipuSDKProvider sdkProvider = new ZhipuSDKProvider(
                    name + "-sdk",
                    credential.getApiKey(),
                    credential.getBaseUrl() != null ? credential.getBaseUrl() : "https://open.bigmodel.cn/api/paas/v4/",
                    credential.getEnableTokenCache(),
                    credential.getTokenExpire()
            );

            HttpApiProvider codingProvider = new HttpApiProvider(
                    name + "-coding",
                    credential.getApiKey(),
                    "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
                    Set.of("glm-4-code", "codegeex-4")
            );

            return List.of(sdkProvider, codingProvider);
        }

        // 可扩展其他LLM服务商...
        log.debug("Provider {} 不支持LLM服务", name);
        return List.of();
    }

    /**
     * 根据模型名称获取对应的Provider
     */
    public BaseLLMProvider getProviderByModel(String model) {
        BaseLLMProvider provider = modelProviderMap.get(model);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的LLM模型: " + model + "，支持的模型: " + modelProviderMap.keySet());
        }
        return provider;
    }

    /**
     * 非流式对话
     *
     * @param messages 消息列表
     * @param options  LLM选项（包含模型名称）
     */
    public String chat(List<BaseLLMProvider.ChatMessage> messages, BaseLLMProvider.LLMOptions options) {
        BaseLLMProvider provider = getProviderByModel(options.getModel());
        return provider.chat(messages, options);
    }

    /**
     * 流式对话
     *
     * @param messages 消息列表
     * @param options  LLM选项（包含模型名称）
     */
    public Flux<BaseLLMProvider.LLMResponse> chatStream(List<BaseLLMProvider.ChatMessage> messages, BaseLLMProvider.LLMOptions options) {
        BaseLLMProvider provider = getProviderByModel(options.getModel());
        return provider.chatStream(messages, options);
    }

    /**
     * 检查是否支持指定模型
     */
    public boolean supportsModel(String model) {
        return modelProviderMap.containsKey(model);
    }

    /**
     * 获取所有支持的LLM模型
     */
    public String[] getSupportedModels() {
        return modelProviderMap.keySet().toArray(new String[0]);
    }

    /**
     * 根据名称获取Provider
     */
    public BaseLLMProvider getProvider(String name) {
        BaseLLMProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("未找到Provider: " + name);
        }
        return provider;
    }

    /**
     * 获取所有Provider名称
     */
    public String[] getProviderNames() {
        return providers.keySet().toArray(new String[0]);
    }

    /**
     * 检查Provider是否存在
     */
    public boolean hasProvider(String name) {
        return providers.containsKey(name);
    }
}
