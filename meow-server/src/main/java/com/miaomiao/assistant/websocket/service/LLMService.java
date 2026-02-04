package com.miaomiao.assistant.websocket.service;

import com.miaomiao.assistant.model.llm.AppChatMessage;
import com.miaomiao.assistant.model.llm.AppLLMResponse;
import com.miaomiao.assistant.model.llm.LLMManager;
import com.miaomiao.assistant.model.llm.LLMOptions;
import com.miaomiao.assistant.websocket.ConversationConfig;
import com.miaomiao.assistant.websocket.message.WebSocketMessageSender;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM（大语言模型）处理服务 负责流式对话和句子分段
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    // 首句标点符号（更激进，更低延迟）
    private static final String FIRST_SENTENCE_PUNCTUATIONS = "，,、。.？?！!；;：:~";
    // 后续句子标点符号
    private static final String SENTENCE_PUNCTUATIONS = "。.？?！!；;：:";

    private final LLMManager llmManager;
    private final WebSocketMessageSender messageSender;

    /**
     * 处理LLM流式对话
     *
     * @param state 会话状态
     * @param text 用户输入文本
     * @param config 对话配置
     * @return 句子流（用于TTS处理）
     */
    public Flux<String> processLLMStream(SessionState state, String text, ConversationConfig config) {
        // 构建LLM选项
        LLMOptions llmOptions = LLMOptions.of(config.getLlmModel());

        // 构建对话历史
        List<AppChatMessage> messages = new ArrayList<>(state.getConversationHistory());
        messages.add(new AppChatMessage("user", text));

        // 设置文本分段sink用于TTS
        Sinks.Many<String> textSink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder fullResponse = new StringBuilder();
        AtomicInteger sentenceIndex = new AtomicInteger(0);
        StringBuilder currentBuffer = new StringBuilder();

        // 标记首句是否已发送（用于激进的首句切分）
        final boolean[] firstSentenceSent = {false};

        // 开始LLM流式响应
        Flux<AppLLMResponse> llmStream = llmManager.chatStream(config.getLMModelKey(), messages, llmOptions);

        // 订阅LLM流 - 检测句子边界时立即发送给TTS
        Disposable llmDisposable = llmStream
                .takeWhile(response -> !state.isAborted()) // 检查中止状态
                .subscribe(
                        llmResponse -> handleLLMResponse(
                                state, llmResponse, text, fullResponse, currentBuffer,
                                sentenceIndex, firstSentenceSent, textSink
                        ),
                        error -> {
                            log.error("LLM流错误", error);
                            textSink.tryEmitComplete();
                        },
                        () -> {
                            log.debug("LLM流完成");
                            // 如果是被中止的，确保 sink 完成
                            if (state.isAborted()) {
                                textSink.tryEmitComplete();
                            }
                        }
                );

        // 保存订阅以便取消
        state.setActiveDisposable(llmDisposable);

        return textSink.asFlux();
    }

    /**
     * 处理LLM响应
     */
    private void handleLLMResponse(SessionState state,
            AppLLMResponse appLlmResponse,
            String userText,
            StringBuilder fullResponse,
            StringBuilder currentBuffer,
            AtomicInteger sentenceIndex,
            boolean[] firstSentenceSent,
            Sinks.Many<String> textSink) {
        String content = appLlmResponse.text();
        if (content != null && !content.isEmpty()) {
            fullResponse.append(content);
            currentBuffer.append(content);

            // 检测句子边界
            String bufferText = currentBuffer.toString();
            String punctuations = firstSentenceSent[0] ? SENTENCE_PUNCTUATIONS : FIRST_SENTENCE_PUNCTUATIONS;

            int lastPunctPos = findLastPunctuation(bufferText, punctuations);
            if (lastPunctPos >= 0) {
                // 提取到标点前的完整句子
                String sentenceText = bufferText.substring(0, lastPunctPos + 1);
                String remaining = bufferText.substring(lastPunctPos + 1);

                // 发送句子标记
                int idx = sentenceIndex.getAndIncrement();
                try {
                    messageSender.sendSentence(state, sentenceText, idx);
                } catch (IOException e) {
                    log.error("发送句子消息失败", e);
                }

                // 立即发送给TTS流
                textSink.tryEmitNext(sentenceText);
                firstSentenceSent[0] = true;

                // 保留剩余文本在缓冲区
                currentBuffer.setLength(0);
                currentBuffer.append(remaining);
            }
        }

        // 流结束时处理剩余文本
        if (appLlmResponse.finished()) {
            if (!currentBuffer.isEmpty()) {
                int idx = sentenceIndex.getAndIncrement();
                try {
                    messageSender.sendSentence(state, currentBuffer.toString(), idx);
                } catch (IOException e) {
                    log.error("发送句子消息失败", e);
                }
                textSink.tryEmitNext(currentBuffer.toString());
            }
            textSink.tryEmitComplete();

            // 保存到对话历史
            state.addMessage("user", userText);
            state.addMessage("assistant", fullResponse.toString());
        }
    }

    /**
     * 查找文本中最后一个标点符号的位置
     */
    private int findLastPunctuation(String text, String punctuations) {
        int lastPos = -1;
        for (int i = 0; i < punctuations.length(); i++) {
            char punct = punctuations.charAt(i);
            int pos = text.lastIndexOf(punct);
            if (pos > lastPos) {
                lastPos = pos;
            }
        }
        return lastPos;
    }
}
