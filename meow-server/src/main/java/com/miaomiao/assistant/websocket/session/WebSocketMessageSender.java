package com.miaomiao.assistant.websocket.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaomiao.assistant.websocket.message.LLMTokenMessage;
import com.miaomiao.assistant.websocket.message.STTMessage;
import com.miaomiao.assistant.websocket.message.WSMessage;
import com.miaomiao.assistant.websocket.protocol.BinaryAudioFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket消息发送器
 * 负责将各类消息序列化并发送到客户端
 */
@Slf4j
@Component
public class WebSocketMessageSender {

    private final ObjectMapper objectMapper;

    public WebSocketMessageSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 发送通用消息（线程安全）
     */
    public void sendMessage(WebSocketSession session, WSMessage message) throws IOException {
        if (session == null || !session.isOpen()) {
            log.warn("WebSocket会话已关闭，无法发送消息");
            return;
        }
        String json = objectMapper.writeValueAsString(message);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    /**
     * 发送通用消息（使用SessionState）
     */
    public void sendMessage(SessionState state, WSMessage message) throws IOException {
        sendMessage(state.getSession(), message);
    }

    /**
     * 发送LLM流式Token消息（用于前端打字效果）
     *
     * @param state       会话状态
     * @param token       当前token
     * @param accumulated 累积文本
     * @param finished    是否完成
     */
    public void sendLLMToken(SessionState state, String token, String accumulated, boolean finished) throws IOException {
        LLMTokenMessage message = new LLMTokenMessage();
        message.setType("llm_token");
        message.setToken(token);
        message.setAccumulated(accumulated);
        message.setFinished(finished);
        message.setTimestamp(System.currentTimeMillis());
        sendMessage(state, message);
    }

    /**
     * 发送STT结果消息
     */
    public void sendSTTResult(SessionState state, String text, boolean isFinal) throws IOException {
        STTMessage sttMessage = new STTMessage();
        sttMessage.setType("stt");
        sttMessage.setText(text);
        sttMessage.setFinal(isFinal);
        sttMessage.setTimestamp(System.currentTimeMillis());
        sendMessage(state, sttMessage);
    }

    /**
     * 发送TTS音频消息
     *
     * @param state    会话状态
     * @param opusData Opus音频数据
     * @param finished 是否是本段TTS的最后一帧
     */
    public void sendTTSAudio(SessionState state, byte[] opusData, boolean finished) throws IOException {
        if (state == null || state.getSession() == null || !state.getSession().isOpen()) {
            log.warn("WebSocket会话已关闭，无法发送TTS音频");
            return;
        }

        WebSocketSession session = state.getSession();
        byte[] payload = opusData == null ? new byte[0] : opusData;
        byte[] frameBytes = BinaryAudioFrame.serverTTS("opus", payload, finished).encode();

        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new BinaryMessage(frameBytes));
            }
        }
    }

    /**
     * 发送错误消息（线程安全）
     */
    public void sendError(WebSocketSession session, String error) throws IOException {
        if (session == null || !session.isOpen()) {
            log.warn("WebSocket会话已关闭，无法发送错误消息");
            return;
        }
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("type", "error");
        errorData.put("message", error);
        errorData.put("timestamp", System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(errorData);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    /**
     * 发送错误消息（使用SessionState）
     */
    public void sendError(SessionState state, String error) throws IOException {
        sendError(state.getSession(), error);
    }
}
