package com.miaomiao.assistant;

import net.labymod.opus.OpusCodec;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Opus文件解码播放测试工具
 * 用于播放tts_output目录下指定会话文件夹中的opus音频文件
 */
public class OpusPlayerTest {

    // Opus解码参数（与编码器保持一致）
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNELS = 1;
    private static final int FRAME_SIZE = 480;
    private static final int BITRATE = 64000;

    public static void main(String[] args) throws Exception {
        // 指定要播放的会话文件夹（tts_output下的具体文件夹）
//        String sessionFolder = "8a2c7b92-1192-5b17-54a9-d3199c22485f_20260205_163524";
        String sessionFolder = "de851161-2900-1a1a-e360-258b834e3733_20260205_165941";

        // 构建完整路径
        Path basePath = Paths.get("..","meow/tts_output", sessionFolder).toAbsolutePath().normalize();
        
        System.out.println("========================================");
        System.out.println("Opus音频播放器");
        System.out.println("========================================");
        System.out.println("会话文件夹: " + basePath);
        
        if (!Files.exists(basePath)) {
            System.err.println("错误: 文件夹不存在 - " + basePath);
            System.err.println("请确保tts_output目录下存在该会话文件夹");
            return;
        }
        
        // 加载native库
        loadNativeLibrary();
        
        // 获取文件夹中所有opus文件并排序
        List<Path> opusFiles = Files.list(basePath)
                .filter(p -> p.toString().endsWith(".opus"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
        
        if (opusFiles.isEmpty()) {
            System.err.println("错误: 文件夹中没有opus文件");
            return;
        }
        
        System.out.println("找到 " + opusFiles.size() + " 个opus文件");
        System.out.println("----------------------------------------");
        
        // 创建Opus解码器
        OpusCodec opusCodec = OpusCodec.newBuilder()
                .withSampleRate(SAMPLE_RATE)
                .withChannels(CHANNELS)
                .withBitrate(BITRATE)
                .withFrameSize(FRAME_SIZE)
                .build();
        
        // 设置音频播放格式
        AudioFormat audioFormat = new AudioFormat(
                SAMPLE_RATE,    // 采样率
                16,             // 采样位数
                CHANNELS,       // 声道数
                true,           // signed
                false           // little endian
        );
        
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine audioLine = (SourceDataLine) AudioSystem.getLine(info);
        audioLine.open(audioFormat);
        audioLine.start();
        
        System.out.println("开始播放...");
        System.out.println();
        
        // 依次解码并播放每个opus文件
        for (Path opusFile : opusFiles) {
            System.out.println("播放: " + opusFile.getFileName());
            
            byte[] opusData = Files.readAllBytes(opusFile);
            byte[] pcmData = decodeOpusData(opusCodec, opusData);
            
            // 播放PCM数据
            audioLine.write(pcmData, 0, pcmData.length);
        }
        
        // 等待播放完成
        audioLine.drain();
        audioLine.close();
        
        // 清理资源
        opusCodec.destroy();
        
        System.out.println();
        System.out.println("----------------------------------------");
        System.out.println("播放完成!");
    }
    
    /**
     * 解码Opus数据为PCM
     * Opus数据格式: [2字节帧长度(小端序)][帧数据] 重复...
     */
    private static byte[] decodeOpusData(OpusCodec opusCodec, byte[] opusData) {
        ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
        int offset = 0;
        
        while (offset + 2 <= opusData.length) {
            // 读取帧长度（2字节小端序）
            int frameLength = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
            offset += 2;
            
            if (offset + frameLength > opusData.length) {
                System.err.println("警告: 帧数据不完整，跳过剩余数据");
                break;
            }
            
            // 提取帧数据
            byte[] frameData = new byte[frameLength];
            System.arraycopy(opusData, offset, frameData, 0, frameLength);
            offset += frameLength;
            
            // 解码帧
            try {
                byte[] pcmFrame = opusCodec.decodeFrame(frameData);
                pcmOutput.write(pcmFrame);
            } catch (Exception e) {
                System.err.println("解码帧失败: " + e.getMessage());
            }
        }
        
        return pcmOutput.toByteArray();
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
