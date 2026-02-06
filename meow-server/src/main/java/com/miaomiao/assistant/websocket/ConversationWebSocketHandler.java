package com.miaomiao.assistant.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.websocket.message.WSMessage;
import com.miaomiao.assistant.websocket.handler.MessageHandlerRegistry;
import com.miaomiao.assistant.websocket.session.WebSocketMessageSender;
import com.miaomiao.assistant.websocket.session.SessionManager;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket对话处理器
 * 负责WebSocket连接管理和消息路由
 * 具体消息处理逻辑委托给各个MessageHandler
 */
@Slf4j
@Component
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;
    private final MessageHandlerRegistry handlerRegistry;
    private final WebSocketMessageSender messageSender;

    public ConversationWebSocketHandler(ObjectMapper objectMapper,
                                        SessionManager sessionManager,
                                        MessageHandlerRegistry handlerRegistry,
                                        WebSocketMessageSender messageSender) {
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
        this.handlerRegistry = handlerRegistry;
        this.messageSender = messageSender;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket连接建立: {}", session.getId());
        sessionManager.createSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            String payload = message.getPayload();
            WSMessage wsMessage = objectMapper.readValue(payload, WSMessage.class);

            SessionState state = sessionManager.getSession(session.getId()).orElse(null);
            if (state == null) {
                log.warn("Session状态未找到: {}", session.getId());
                return;
            }

            // 使用策略模式分发消息到对应处理器
            if (!handlerRegistry.dispatch(state, wsMessage)) {
                log.warn("未找到消息处理器，消息类型: {}", wsMessage.getType());
            }

        } catch (Exception e) {
            log.error("处理WebSocket消息失败", e);
            messageSender.sendError(session, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket连接关闭: {}, 状态: {}", session.getId(), status);
        sessionManager.removeSession(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误, 会话: {}", session.getId(), exception);
        sessionManager.removeSession(session.getId());
    }
}
