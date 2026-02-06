package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.message.WSMessage;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息处理器注册表
 * 管理所有消息处理器，根据消息类型分发到对应处理器
 */
@Slf4j
@Component
public class MessageHandlerRegistry {

    private final Map<String, MessageHandler<? extends WSMessage>> handlers = new ConcurrentHashMap<>();

    /**
     * 通过构造函数自动注入所有MessageHandler实现
     */
    public MessageHandlerRegistry(List<MessageHandler<? extends WSMessage>> handlerList) {
        for (MessageHandler<? extends WSMessage> handler : handlerList) {
            registerHandler(handler);
        }
        log.info("已注册 {} 个消息处理器: {}", handlers.size(), handlers.keySet());
    }

    /**
     * 注册消息处理器
     */
    public void registerHandler(MessageHandler<? extends WSMessage> handler) {
        String type = handler.getMessageType();
        if (handlers.containsKey(type)) {
            log.warn("消息处理器已存在，将被覆盖: {}", type);
        }
        handlers.put(type, handler);
        log.debug("注册消息处理器: {} -> {}", type, handler.getClass().getSimpleName());
    }

    /**
     * 获取消息处理器
     */
    public Optional<MessageHandler<? extends WSMessage>> getHandler(String messageType) {
        return Optional.ofNullable(handlers.get(messageType));
    }

    /**
     * 分发消息到对应的处理器
     *
     * @return true 如果找到处理器并成功处理，false 如果未找到处理器
     */
    @SuppressWarnings("unchecked")
    public boolean dispatch(SessionState state, WSMessage message) throws IOException {
        String type = message.getType();
        if (type == null) {
            log.warn("消息类型为空，无法分发");
            return false;
        }

        MessageHandler<WSMessage> handler = (MessageHandler<WSMessage>) handlers.get(type);
        if (handler == null) {
            log.warn("未找到消息处理器: {}", type);
            return false;
        }

        handler.handle(state, message);
        return true;
    }

    /**
     * 检查是否支持某种消息类型
     */
    public boolean supports(String messageType) {
        return handlers.containsKey(messageType);
    }
}
