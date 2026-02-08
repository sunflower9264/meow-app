package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.model.llm.AppChatMessage;
import com.miaomiao.assistant.model.llm.AppLLMResponse;
import com.miaomiao.assistant.model.llm.LLMManager;
import com.miaomiao.assistant.model.llm.LLMOptions;
import com.miaomiao.assistant.service.SystemPromptService;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.session.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM（大语言模型）处理服务
 * <p>
 * 职责：
 * 1. 流式调用LLM获取响应
 * 2. 发送token给前端（用于打字效果）
 * 3. 把原始token流发给TTS pipeline（由TextAggregator断句）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final LLMManager llmManager;
    private final WebSocketMessageSender messageSender;
    private final SystemPromptService systemPromptService;

    /**
     * 处理LLM流式对话
     *
     * @param state  会话状态
     * @param text   用户输入文本
     * @param config 对话配置
     * @return token流（用于TTS处理，由TextAggregator断句）
     */
    public Flux<String> processLLMStream(SessionState state, String text, ConversationConfig config) {
        // 构建LLM选项
        LLMOptions llmOptions = LLMOptions.of(config.getLlmModel());
        llmOptions.setMaxTokens(config.getMaxTokens());

        // 获取系统提示词
        String systemPrompt = systemPromptService.getSystemPrompt(
                config.getCharacterId(),
                config.getMaxTokens()
        );

        // 构建对话历史（系统提示词 + 历史 + 当前输入）
        List<AppChatMessage> messages = new ArrayList<>();
        messages.add(new AppChatMessage("system", systemPrompt));
        messages.addAll(state.getConversationHistory());
        messages.add(new AppChatMessage("user", text));

        // 设置token sink用于TTS pipeline
        Sinks.Many<String> tokenSink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder fullResponse = new StringBuilder();

        // 开始LLM流式响应
        Flux<AppLLMResponse> llmStream = llmManager.chatStream(config.getLMModelKey(), messages, llmOptions);

        // 订阅LLM流 - 直接把token发给TTS pipeline
        Disposable llmDisposable = llmStream
                .takeWhile(response -> !state.isAborted())
                .doFinally(signalType -> {
                    log.debug("LLM流结束: session={}, signal={}", state.getSessionId(), signalType);
                    tokenSink.tryEmitComplete();
                })
                .subscribe(
                        llmResponse -> handleLLMResponse(state, llmResponse, text, fullResponse, tokenSink),
                        error -> {
                            log.error("LLM流错误", error);
                        },
                        () -> {
                            log.debug("LLM流完成");
                        }
                );

        // 保存订阅以便取消
        state.setActiveDisposable(llmDisposable);

        return tokenSink.asFlux();
    }

    /**
     * 处理LLM响应
     * <p>
     * 只做两件事：
     * 1. 发送token给前端（打字效果）
     * 2. 把token发给TTS pipeline（由TextAggregator断句）
     */
    private void handleLLMResponse(SessionState state,
                                   AppLLMResponse appLlmResponse,
                                   String userText,
                                   StringBuilder fullResponse,
                                   Sinks.Many<String> tokenSink) {
        String content = appLlmResponse.text();
        if (content != null && !content.isEmpty()) {
            fullResponse.append(content);

            // 记录 LLM 首次响应时间（性能指标）
            state.getPerformanceMetrics().recordLLMFirstResponse();

            // 1. 发送流式token给前端（用于打字效果）
            try {
                messageSender.sendLLMToken(state, content, fullResponse.toString(), false);
            } catch (IOException e) {
                log.error("发送LLM token消息失败", e);
            }

            // 2. 把token发给TTS pipeline（由TextAggregator断句）
            tokenSink.tryEmitNext(content);
        }

        // 流结束
        if (appLlmResponse.finished()) {
            if (fullResponse.isEmpty()) {
                log.warn("LLM流结束但无文本输出: session={}, userTextLen={}",
                        state.getSessionId(), userText == null ? 0 : userText.length());
            }
            tokenSink.tryEmitComplete();

            // 发送完成标记给前端
            try {
                messageSender.sendLLMToken(state, "", fullResponse.toString(), true);
            } catch (IOException e) {
                log.error("发送LLM完成消息失败", e);
            }

            // 保存到对话历史
            state.addMessage("user", userText);
            state.addMessage("assistant", fullResponse.toString());
        }
    }
}
