package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.message.TerminateMessage;
import com.miaomiao.assistant.websocket.service.ConversationService;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Terminate message handler.
 */
@Slf4j
@Component
public class TerminateMessageHandler implements MessageHandler<TerminateMessage> {

    private final ConversationService conversationService;

    public TerminateMessageHandler(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public String getMessageType() {
        return "terminate";
    }

    @Override
    public void handle(SessionState state, TerminateMessage message) {
        log.info("收到终止请求: session={}", state.getSessionId());
        conversationService.terminateCurrentResponse(state);
    }
}
