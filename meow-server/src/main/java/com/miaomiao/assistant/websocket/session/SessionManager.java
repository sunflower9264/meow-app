package com.miaomiao.assistant.websocket.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket会话管理器
 * 负责管理所有WebSocket连接的会话状态
 */
@Slf4j
@Component
public class SessionManager {

    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * 创建新会话
     */
    public SessionState createSession(WebSocketSession session) {
        SessionState state = new SessionState(session);
        sessionStates.put(session.getId(), state);
        log.debug("创建会话状态: {}", session.getId());
        return state;
    }

    /**
     * 获取会话状态
     */
    public Optional<SessionState> getSession(String sessionId) {
        return Optional.ofNullable(sessionStates.get(sessionId));
    }

    /**
     * 获取会话状态，如果不存在则抛出异常
     */
    public SessionState getSessionOrThrow(String sessionId) {
        return getSession(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话状态未找到: " + sessionId));
    }

    /**
     * 移除并清理会话
     */
    public void removeSession(String sessionId) {
        SessionState state = sessionStates.remove(sessionId);
        if (state != null) {
            state.cleanup();
            log.debug("移除会话状态: {}", sessionId);
        }
    }

    /**
     * 获取当前活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessionStates.size();
    }
}
