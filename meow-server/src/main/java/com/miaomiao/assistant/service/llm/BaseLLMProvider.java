package com.miaomiao.assistant.service.llm;

import lombok.Getter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/**
 * LLM提供商抽象基类
 * 定义统一的LLM调用接口
 */
public abstract class BaseLLMProvider {

    @Getter
    protected String providerName;
    protected String apiKey;
    protected String baseUrl;

    /**
     * LLM响应结果
     */
    public record AppLLMResponse(String text, boolean finished) {
    }

    /**
     * 聊天消息
     *
     * @param role system, user, assistant
     */
    public record AppChatMessage(String role, String content) {
    }

    /**
     * LLM请求选项
     */
    @Getter
    public static class LLMOptions {
        private String model;
        private Double temperature = 0.7;
        private Integer maxTokens = 2000;

        public static LLMOptions of(String model) {
            LLMOptions options = new LLMOptions();
            options.model = model;
            return options;
        }

        public static LLMOptions of(String model, Double temperature, Integer maxTokens) {
            LLMOptions options = of(model);
            options.temperature = temperature;
            options.maxTokens = maxTokens;
            return options;
        }

        public LLMOptions temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public LLMOptions maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }
    }

    /**
     * 检查是否支持指定模型
     */
    public abstract boolean supportsModel(String model);

    /**
     * 获取支持的模型列表
     */
    public abstract Set<String> getSupportedModels();

    /**
     * 非流式对话
     *
     * @param messages 消息列表
     * @param options  LLM选项（包含模型名称）
     * @return 完整响应文本
     */
    public abstract String chat(List<AppChatMessage> messages, LLMOptions options);

    /**
     * 流式对话
     *
     * @param messages 消息列表
     * @param options  LLM选项（包含模型名称）
     * @return 响应流
     */
    public abstract Flux<AppLLMResponse> chatStream(List<AppChatMessage> messages, LLMOptions options);
}
