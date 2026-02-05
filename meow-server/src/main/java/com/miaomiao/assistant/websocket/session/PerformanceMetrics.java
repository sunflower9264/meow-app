package com.miaomiao.assistant.websocket.session;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能指标跟踪类
 * <p>
 * 用于记录从用户输入到各阶段的时间：
 * - 用户输入时间（起点）
 * - LLM 第一次返回时间
 * - TTS 第一次返回时间
 * <p>
 * 同时支持保存 TTS 输出音频到文件
 */
@Slf4j
public class PerformanceMetrics {

    private static final String OUTPUT_DIR = "tts_output";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Getter
    private final String sessionId;

    /**
     * 用户输入开始时间（毫秒）
     */
    private final AtomicLong userInputStartTime = new AtomicLong(0);

    /**
     * LLM 第一次返回时间（毫秒）
     */
    private final AtomicLong llmFirstResponseTime = new AtomicLong(0);

    /**
     * TTS 第一次返回时间（毫秒）
     */
    private final AtomicLong ttsFirstResponseTime = new AtomicLong(0);

    /**
     * 是否已记录 LLM 第一次返回
     */
    private final AtomicBoolean llmFirstResponseRecorded = new AtomicBoolean(false);

    /**
     * 是否已记录 TTS 第一次返回
     */
    private final AtomicBoolean ttsFirstResponseRecorded = new AtomicBoolean(false);

    /**
     * TTS 音频文件序号
     */
    private final AtomicInteger ttsAudioFileIndex = new AtomicInteger(0);

    /**
     * 当前会话的输出目录
     */
    private final String sessionOutputDir;

    /**
     * 是否启用音频保存
     * -- SETTER --
     *  设置是否启用音频保存

     */
    @Setter
    @Getter
    private volatile boolean audioSaveEnabled = true;

    /**
     * 异步文件保存线程池（单线程，保证顺序）
     */
    private final ExecutorService fileSaveExecutor;

    public PerformanceMetrics(String sessionId) {
        this.sessionId = sessionId;
        // 创建会话特定的输出目录
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        this.sessionOutputDir = OUTPUT_DIR + File.separator + sessionId + "_" + timestamp;
        // 创建单线程线程池用于异步保存文件
        this.fileSaveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TTS-FileSave-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        ensureOutputDirectoryExists();
    }

    /**
     * 确保输出目录存在
     */
    private void ensureOutputDirectoryExists() {
        File dir = new File(sessionOutputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * 记录用户输入开始时间
     */
    public void recordUserInputStart() {
        long now = System.currentTimeMillis();
        userInputStartTime.set(now);
        // 重置其他状态
        llmFirstResponseRecorded.set(false);
        ttsFirstResponseRecorded.set(false);
        llmFirstResponseTime.set(0);
        ttsFirstResponseTime.set(0);
    }

    /**
     * 记录 LLM 第一次返回时间
     */
    public void recordLLMFirstResponse() {
        if (llmFirstResponseRecorded.compareAndSet(false, true)) {
            long now = System.currentTimeMillis();
            llmFirstResponseTime.set(now);
        }
    }

    /**
     * 记录 TTS 第一次返回时间
     */
    public void recordTTSFirstResponse() {
        if (ttsFirstResponseRecorded.compareAndSet(false, true)) {
            long now = System.currentTimeMillis();
            ttsFirstResponseTime.set(now);

            long llmElapsed = llmFirstResponseTime.get() - userInputStartTime.get();
            long ttsElapsed = now - userInputStartTime.get();
            long ttsFromLlm = now - llmFirstResponseTime.get();

            log.info("[性能指标] 会话={} TTS首次响应 " +
                    "用户输入->LLM首次={}ms, " +
                    "用户输入->TTS首次={}ms, " +
                    "LLM首次->TTS首次={}ms",
                    sessionId, llmElapsed, ttsElapsed, ttsFromLlm);
        }
    }

    /**
     * 保存 TTS 音频到文件（异步）
     *
     * @param audioData OPUS 音频数据
     */
    public void saveTTSAudio(byte[] audioData) {
        if (!audioSaveEnabled || audioData == null || audioData.length == 0) {
            return;
        }

        int index = ttsAudioFileIndex.incrementAndGet();
        String filename = String.format("tts_%03d.opus", index);
        String filePath = sessionOutputDir + File.separator + filename;

        // 复制数据，避免异步执行时数据被修改
        byte[] dataCopy = audioData.clone();

        fileSaveExecutor.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(dataCopy);
            } catch (IOException e) {
                log.error("保存 TTS 音频失败: {}", filePath, e);
            }
        });
    }

    /**
     * 保存 PCM 音频到文件（异步）
     *
     * @param pcmData PCM 音频数据
     */
    public void savePCMAudio(byte[] pcmData) {
        if (!audioSaveEnabled || pcmData == null || pcmData.length == 0) {
            return;
        }

        int index = ttsAudioFileIndex.incrementAndGet();
        String filename = String.format("tts_%03d.pcm", index);
        String filePath = sessionOutputDir + File.separator + filename;

        // 复制数据，避免异步执行时数据被修改
        byte[] dataCopy = pcmData.clone();

        fileSaveExecutor.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(dataCopy);
            } catch (IOException e) {
                log.error("保存 PCM 音频失败: {}", filePath, e);
            }
        });
    }

    /**
     * 关闭线程池，等待所有文件保存完成
     */
    public void shutdown() {
        fileSaveExecutor.shutdown();
        try {
            if (!fileSaveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                fileSaveExecutor.shutdownNow();
                log.warn("文件保存线程池关闭超时，强制终止");
            }
        } catch (InterruptedException e) {
            fileSaveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
