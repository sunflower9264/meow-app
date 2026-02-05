package com.miaomiao.assistant.codec;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.labymod.opus.OpusCodec;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * Opus音频编码器
 * 使用opus-jni实现
 * 参考: https://github.com/LabyMod/opus-jni
 */
@Slf4j
@Component
public class OpusEncoder {

    // Opus编码参数
    private static final int SAMPLE_RATE = 24000;     // 智谱TTS返回的采样率
    private static final int CHANNELS = 1;            // 单声道
    private static final int BITRATE = 64000;         // 64kbps
    // frameSize = 采样率 * 帧时长(秒)
    // 对于24kHz，20ms帧: 24000 * 0.02 = 480
    private static final int FRAME_SIZE = 480;

    private final OpusCodec opusCodec;

    public OpusEncoder() {
        // 使用opus-jni的OpusCodec Builder
        this.opusCodec = OpusCodec.newBuilder()
                .withSampleRate(SAMPLE_RATE)
                .withChannels(CHANNELS)
                .withBitrate(BITRATE)
                .withFrameSize(FRAME_SIZE)
                .build();
        log.info("Opus编码器初始化成功: 采样率={}Hz, 声道数={}, 比特率={}bps, 帧大小={}样本",
                SAMPLE_RATE, CHANNELS, BITRATE, FRAME_SIZE);
    }

    /**
     * 将PCM数据编码为Opus
     * 会自动进行分帧处理，输入数据长度可以是任意的
     *
     * @param pcmData 16位PCM数据(小端序)
     * @return Opus编码后的数据（多个帧拼接，每帧前有2字节长度头）
     */
    public byte[] encodePcmToOpus(byte[] pcmData) {
        try {
            int frameSizeBytes = FRAME_SIZE * CHANNELS * 2;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            // 分帧编码
            int offset = 0;
            while (offset + frameSizeBytes <= pcmData.length) {
                // 提取一帧数据
                byte[] frameData = new byte[frameSizeBytes];
                System.arraycopy(pcmData, offset, frameData, 0, frameSizeBytes);

                // 编码这一帧
                byte[] encoded = opusCodec.encodeFrame(frameData);

                // 写入帧长度（2字节，小端序）和帧数据
                outputStream.write(encoded.length & 0xFF);
                outputStream.write((encoded.length >> 8) & 0xFF);
                outputStream.write(encoded);

                offset += frameSizeBytes;
            }

            // 处理剩余不足一帧的数据：补0凑成完整帧
            int remaining = pcmData.length - offset;
            if (remaining > 0) {
                byte[] frameData = new byte[frameSizeBytes];
                System.arraycopy(pcmData, offset, frameData, 0, remaining);
                // 剩余部分自动为0（Java数组初始化）

                byte[] encoded = opusCodec.encodeFrame(frameData);
                outputStream.write(encoded.length & 0xFF);
                outputStream.write((encoded.length >> 8) & 0xFF);
                outputStream.write(encoded);
            }
            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("PCM转Opus编码失败", e);
            throw new RuntimeException("音频编码失败", e);
        }
    }

    /**
     * 销毁编码器，释放资源
     */
    @PreDestroy
    public void destroy() {
        if (opusCodec != null) {
            opusCodec.destroy();
            log.info("Opus编码器已销毁");
        }
    }
}
