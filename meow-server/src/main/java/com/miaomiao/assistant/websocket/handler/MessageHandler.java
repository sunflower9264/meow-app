package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.message.WSMessage;
import com.miaomiao.assistant.websocket.session.SessionState;

import java.io.IOException;

/**
 * 消息处理器接口
 * 使用策略模式处理不同类型的WebSocket消息
 */
public interface MessageHandler<T extends WSMessage> {

    /**
     * 获取处理器支持的消息类型
     * 对应WSMessage中的type字段
     */
    String getMessageType();

    /**
     * 获取处理器支持的消息类
     */
    Class<T> getMessageClass();

    /**
     * 处理消息
     *
     * @param state   会话状态
     * @param message 消息对象
     */
    void handle(SessionState state, T message) throws IOException;
}
