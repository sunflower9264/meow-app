package com.miaomiao.assistant.session.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ConversationWebSocketHandler conversationHandler;

    public WebSocketConfig(ConversationWebSocketHandler conversationHandler) {
        this.conversationHandler = conversationHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(conversationHandler, "/ws/conversation")
                .setAllowedOrigins("*");  // Configure CORS as needed
    }
}
