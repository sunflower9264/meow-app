package com.miaomiao.assistant;

import cn.hutool.core.io.FileUtil;
import com.miaomiao.assistant.codec.OpusCodec;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Opus编解码测试工具
 * 用于测试PCM -> Opus编码、播放、Opus -> PCM解码的完整流程
 */
public class OpusPlayTest {

    // Opus编解码参数（与编码器保持一致）
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNELS = 1;

    public static void main(String[] args) throws Exception {
        loadNativeLibrary();
        OpusCodec opusCodec = new OpusCodec();
        String path = "D:\\myworkspace\\meow\\tts_output\\6acf62aa-2178-3222-e851-78b0a378616e_20260206_133123";

        List<File> files = FileUtil.loopFiles(path);
        for (File file : files) {
            byte[] originalOpus = FileUtil.readBytes(file);
            byte[] rawOpusDecodedPcm = opusCodec.decodeOpusToPcm(originalOpus);
            playPcmAudio(rawOpusDecodedPcm);
        }
    }

    /**
     * 播放PCM音频数据
     *
     * @param pcmData 16位PCM数据(小端序)
     */
    private static void playPcmAudio(byte[] pcmData) throws Exception {
        AudioFormat format = new AudioFormat(
                SAMPLE_RATE,
                16,              // 16位样本
                CHANNELS,        // 单声道
                true,            // 有符号
                false            // 小端序
        );

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("音频格式不支持: " + format);
        }

        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        System.out.println("开始播放... (音频长度: " + (pcmData.length / format.getFrameSize() * 1000.0 / SAMPLE_RATE) + " ms)");

        // 分块写入，避免一次性写入过多数据
        int bufferSize = 9600; // 约100ms的数据
        int offset = 0;
        while (offset < pcmData.length) {
            int remaining = pcmData.length - offset;
            int toWrite = Math.min(bufferSize, remaining);
            line.write(pcmData, offset, toWrite);
            offset += toWrite;
        }

        // 等待播放完成
        line.drain();
        line.close();
        System.out.println("播放完成");
    }

    /**
     * 加载Opus native库
     */
    private static void loadNativeLibrary() {
        String osName = System.getProperty("os.name").toLowerCase();
        String platform = osName.contains("win") ? "windows-x64" : "linux-x64";
        String libraryFileName = osName.contains("win") ? "opus-jni-native.dll" : "libopus-jni-native.so";

        // 尝试从native目录加载
        Path nativePath = Paths.get("meow-server/native", platform, libraryFileName).toAbsolutePath();

        if (Files.exists(nativePath)) {
            System.load(nativePath.toString());
            System.out.println("Native库加载成功: " + nativePath);
        } else {
            throw new RuntimeException("找不到native库: " + nativePath);
        }
    }
}
