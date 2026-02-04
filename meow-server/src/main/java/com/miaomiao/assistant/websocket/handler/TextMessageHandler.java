package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.dto.StringMessage;
import com.miaomiao.assistant.websocket.service.ConversationService;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文本消息处理器
 * 处理客户端发送的文本输入，直接进入LLM对话流程
 */
@Slf4j
@Component
public class TextMessageHandler implements MessageHandler<StringMessage> {

    private final ConversationService conversationService;

    public TextMessageHandler(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public String getMessageType() {
        return "text";
    }

    @Override
    public Class<StringMessage> getMessageClass() {
        return StringMessage.class;
    }

    @Override
    public void handle(SessionState state, StringMessage message) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            log.warn("收到空文本消息，忽略");
            return;
        }

        log.debug("处理文本输入: {}", text);
        conversationService.processConversation(state, text);
    }
}
