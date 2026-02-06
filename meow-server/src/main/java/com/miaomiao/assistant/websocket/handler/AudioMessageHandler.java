package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.message.AudioMessage;
import com.miaomiao.assistant.websocket.service.ConversationService;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 音频消息处理器 处理客户端发送的音频数据
 */
@Slf4j
@Component
public class AudioMessageHandler implements MessageHandler<AudioMessage> {

    private final ConversationService conversationService;

    public AudioMessageHandler(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public String getMessageType() {
        return "audio";
    }

    @Override
    public void handle(SessionState state, AudioMessage message) {
        // 累积音频数据
        byte[] audioData = message.getData();
        if (audioData != null && audioData.length > 0) {
            state.accumulateAudio(audioData);
            log.debug("累积音频数据: {} 字节", audioData.length);
        }

        // 如果是最后一块音频，处理累积的数据
        if (message.isLast()) {
            byte[] fullAudioData = state.getAndClearAudioBuffer();
            log.debug("音频接收完成，总计 {} 字节，格式: {}, 开始处理", fullAudioData.length, message.getFormat());
            conversationService.processAudioInput(state, fullAudioData, message.getFormat());
        }
    }
}
