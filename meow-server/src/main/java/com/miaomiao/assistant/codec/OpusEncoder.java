package com.miaomiao.assistant.codec;

import com.miaomiao.assistant.config.NativeLibraryLoader;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.labymod.opus.OpusCodec;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;

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

    public OpusEncoder(NativeLibraryLoader nativeLibraryLoader) {
        try {
            // 检查native库是否已由NativeLibraryLoader加载
            if (!nativeLibraryLoader.isLoaded()) {
                throw new RuntimeException("Native库未加载，请检查NativeLibraryLoader配置");
            }

            // native库已由NativeLibraryLoader通过System.load()加载
            // opus-jni会检测到库已加载，直接使用即可
            File nativeDir = nativeLibraryLoader.getNativeDirectory();
            if (nativeDir != null) {
                log.info("使用NativeLibraryLoader加载的native目录: {}", nativeDir.getAbsolutePath());
            }

            // 使用opus-jni的OpusCodec Builder
            this.opusCodec = OpusCodec.newBuilder()
                    .withSampleRate(SAMPLE_RATE)
                    .withChannels(CHANNELS)
                    .withBitrate(BITRATE)
                    .withFrameSize(FRAME_SIZE)
                    .build();

            log.info("Opus编码器初始化成功: 采样率={}Hz, 声道数={}, 比特率={}bps, 帧大小={}样本, 每帧字节数={}",
                    SAMPLE_RATE, CHANNELS, BITRATE, FRAME_SIZE, getFrameSizeBytes());
        } catch (Exception e) {
            log.error("初始化Opus编码器失败", e);
            throw new RuntimeException("Opus编码器初始化失败，请确保native库已正确加载", e);
        }
    }

    /**
     * 将PCM数据编码为Opus
     * 会自动进行分帧处理，输入数据长度可以是任意的
     *
     * @param pcmData    16位PCM数据(小端序)
     * @param sampleRate 采样率（当前忽略，使用编码器配置的采样率）
     * @return Opus编码后的数据（多个帧拼接，每帧前有2字节长度头）
     */
    public byte[] encodePcmToOpus(byte[] pcmData, int sampleRate) {
        try {
            int frameSizeBytes = getFrameSizeBytes();
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
     * 编码单帧PCM数据为Opus
     * 输入数据长度必须等于 getFrameSizeBytes()
     *
     * @param frameData 单帧PCM数据
     * @return 编码后的Opus数据
     */
    public byte[] encodeSingleFrame(byte[] frameData) {
        if (frameData.length != getFrameSizeBytes()) {
            throw new IllegalArgumentException(
                    String.format("帧数据长度必须为%d字节，实际为%d字节", getFrameSizeBytes(), frameData.length));
        }
        return opusCodec.encodeFrame(frameData);
    }

    /**
     * 获取默认采样率 (智谱TTS返回的采样率)
     */
    public int getDefaultSampleRate() {
        return SAMPLE_RATE;
    }

    /**
     * 获取帧大小（采样数）
     */
    public int getFrameSize() {
        return FRAME_SIZE;
    }

    /**
     * 获取帧大小（字节数）
     * = frameSize * channels * 2 (16位采样)
     */
    public int getFrameSizeBytes() {
        return FRAME_SIZE * CHANNELS * 2;
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
