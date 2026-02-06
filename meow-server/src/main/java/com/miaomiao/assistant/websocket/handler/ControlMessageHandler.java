package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.message.ControlMessage;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 控制消息处理器
 * 处理会话控制命令
 */
@Slf4j
@Component
public class ControlMessageHandler implements MessageHandler<ControlMessage> {

    @Override
    public String getMessageType() {
        return "control";
    }

    @Override
    public Class<ControlMessage> getMessageClass() {
        return ControlMessage.class;
    }

    @Override
    public void handle(SessionState state, ControlMessage message) {
        String action = message.getAction();
        if (action == null) {
            log.warn("控制消息缺少action字段");
            return;
        }

        if ("abort".equals(action)) {
            log.info("收到中止命令，会话: {}", state.getSessionId());
            state.abort();
        } else {
            log.warn("未知控制动作: {}", action);
        }
    }
}
