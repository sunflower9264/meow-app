package com.miaomiao.assistant.model.llm;

import com.miaomiao.assistant.model.BaseModelProvider;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM提供商抽象基类
 * 定义统一的LLM调用接口
 */
public abstract class BaseLLMProvider extends BaseModelProvider {

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
