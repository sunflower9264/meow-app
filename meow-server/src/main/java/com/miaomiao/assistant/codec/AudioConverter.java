package com.miaomiao.assistant.codec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 音频格式转换工具类
 */
@Slf4j
@Component
public class AudioConverter {

    private final OpusEncoder opusEncoder;

    public AudioConverter(OpusEncoder opusEncoder) {
        this.opusEncoder = opusEncoder;
    }
    /**
     * 将PCM字节数组转换为Opus（裸帧格式，带长度头）
     *
     * @param pcmData PCM数据(16位小端)
     * @return Opus编码后的数据（裸帧，每帧前有2字节长度头）
     */
    public byte[] convertPcmToOpus(byte[] pcmData) {
        return opusEncoder.encodePcmToOpus(pcmData, opusEncoder.getDefaultSampleRate());
    }
}
