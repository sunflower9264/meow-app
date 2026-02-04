package com.miaomiao.assistant.codec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

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
     * 将Base64编码的PCM转换为Opus
     * 智谱TTS返回的是Base64编码的PCM数据
     *
     * @param base64Pcm Base64编码的PCM数据
     * @return Opus编码后的数据
     */
    public byte[] convertPcmBase64ToOpus(String base64Pcm) {
        try {
            // 解码Base64
            byte[] pcmData = Base64.getDecoder().decode(base64Pcm);

            // 转换为Opus
            return convertPcmToOpus(pcmData);

        } catch (Exception e) {
            log.error("PCM Base64转Opus失败", e);
            throw new RuntimeException("音频转换失败", e);
        }
    }

    /**
     * 将PCM字节数组转换为Opus
     *
     * @param pcmData PCM数据(16位小端)
     * @return Opus编码后的数据
     */
    public byte[] convertPcmToOpus(byte[] pcmData) {
        return opusEncoder.encodePcmToOpus(pcmData, opusEncoder.getDefaultSampleRate());
    }

    /**
     * 获取PCM采样率
     */
    public int getPcmSampleRate() {
        return opusEncoder.getDefaultSampleRate();
    }

    /**
     * 将16位PCM字节数组(小端)转换为short数组
     */
    public short[] pcmBytesToShorts(byte[] pcmBytes) {
        short[] shorts = new short[pcmBytes.length / 2];
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }

    /**
     * 将short数组转换为16位PCM字节数组(小端)
     */
    public byte[] shortsToPcmBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts);
        return bytes;
    }
}
