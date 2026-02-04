package com.miaomiao.assistant.websocket.handler;

import com.miaomiao.assistant.websocket.dto.AudioMessage;
import com.miaomiao.assistant.websocket.service.ASRService;
import com.miaomiao.assistant.websocket.session.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 音频消息处理器
 * 处理客户端发送的音频数据
 */
@Slf4j
@Component
public class AudioMessageHandler implements MessageHandler<AudioMessage> {

    private final ASRService asrService;

    public AudioMessageHandler(ASRService asrService) {
        this.asrService = asrService;
    }

    @Override
    public String getMessageType() {
        return "audio";
    }

    @Override
    public Class<AudioMessage> getMessageClass() {
        return AudioMessage.class;
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
            log.debug("音频接收完成，总计 {} 字节，开始ASR处理", fullAudioData.length);
            asrService.processAudio(state, fullAudioData);
        }
    }
}
