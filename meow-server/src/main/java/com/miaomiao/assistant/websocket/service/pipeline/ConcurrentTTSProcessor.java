package com.miaomiao.assistant.websocket.service.pipeline;

import com.miaomiao.assistant.codec.OpusCodec;
import com.miaomiao.assistant.model.tts.TTSAudio;
import com.miaomiao.assistant.model.tts.TTSManager;
import com.miaomiao.assistant.model.tts.TTSOptions;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 并发 TTS 处理器
 * <p>
 * 实现 LLM -> TTS 的多线程并发处理，同时保证播放顺序：
 * 1. 使用线程池并发执行 TTS 调用，提高吞吐量
 * 2. 使用有序结果队列，确保音频按句子顺序播放
 * 3. 支持中断和优雅关闭
 * <p>
 * 工作原理：
 * - 生产者线程：接收聚合后的句子，提交到线程池执行TTS转换
 * - 工作线程池：并发执行TTS调用，结果放入有序队列
 * - 消费者线程：按序号顺序从队列取出结果并播放
 *
 * @author Pipecat移植优化
 */
@Slf4j
public class ConcurrentTTSProcessor implements AutoCloseable {

    /**
     * TTS 任务
     */
    @Data
    public static class TTSTask {
        private final int sequence;           // 序号（用于保证播放顺序）
        private final String text;            // 待转换文本
        private final TextAggregator.AggregationType type;  // 聚合类型
        private final String providerModelKey; // TTS 提供者和模型
        private final TTSOptions options;      // TTS 选项

        public TTSTask(int sequence, String text, TextAggregator.AggregationType type,
                       String providerModelKey, TTSOptions options) {
            this.sequence = sequence;
            this.text = text;
            this.type = type;
            this.providerModelKey = providerModelKey;
            this.options = options;
        }
    }

    /**
     * TTS 结果
     */
    @Data
    public static class TTSResult {
        private final int sequence;           // 序号（对应任务序号）
        private final String text;            // 原始文本
        private final byte[] pcmData;         // 原始 PCM 音频数据
        private final byte[] opusData;        // OPUS 编码后的音频数据
        private final boolean success;        // 是否成功
        private final String errorMessage;    // 错误信息（如果失败）

        public static TTSResult success(int sequence, String text, byte[] pcmData, byte[] opusData) {
            return new TTSResult(sequence, text, pcmData, opusData, true, null);
        }

        public static TTSResult failure(int sequence, String text, String errorMessage) {
            return new TTSResult(sequence, text, null, null, false, errorMessage);
        }
    }

    // 依赖组件
    private final TTSManager ttsManager;
    private final OpusCodec opusCodec;

    // 线程池和队列
    private final ExecutorService ttsExecutor;
    private final BlockingQueue<TTSResult> resultQueue;
    private final Thread consumerThread;

    // 状态控制
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);
    private final AtomicInteger expectedSequence = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, TTSResult> pendingResults = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final CountDownLatch completionLatch = new CountDownLatch(1);

    // 回调
    private final BiConsumer<byte[], Boolean> audioSender;  // 音频发送回调 (opusData, isLast)
    private final Consumer<String> errorHandler;             // 错误处理回调
    private final BiConsumer<byte[], byte[]> audioSaver;     // 音频保存回调 (pcmData, opusData)

    // 配置
    private final int maxConcurrency;
    private final int queueCapacity;

    /**
     * 构造函数（带音频保存回调）
     *
     * @param ttsManager     TTS 管理器
     * @param opusCodec    音频转换器
     * @param audioSender    音频发送回调
     * @param errorHandler   错误处理回调
     * @param maxConcurrency 最大并发数（建议 2-4）
     * @param audioSaver     音频保存回调 (pcmData, opusData)
     */
    public ConcurrentTTSProcessor(
            TTSManager ttsManager,
            OpusCodec opusCodec,
            BiConsumer<byte[], Boolean> audioSender,
            Consumer<String> errorHandler,
            int maxConcurrency,
            BiConsumer<byte[], byte[]> audioSaver) {
        this.ttsManager = ttsManager;
        this.opusCodec = opusCodec;
        this.audioSender = audioSender;
        this.errorHandler = errorHandler;
        this.audioSaver = audioSaver;
        this.maxConcurrency = Math.max(1, Math.min(maxConcurrency, 8)); // 限制 1-8
        this.queueCapacity = this.maxConcurrency * 2;

        // 创建固定大小的线程池
        this.ttsExecutor = Executors.newFixedThreadPool(this.maxConcurrency, r -> {
            Thread t = new Thread(r, "TTS-Worker");
            t.setDaemon(true);
            return t;
        });

        // 创建结果队列
        this.resultQueue = new LinkedBlockingQueue<>(queueCapacity);

        // 启动消费者线程（按序播放）
        this.consumerThread = new Thread(this::consumeResults, "TTS-Consumer");
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();
    }

    /**
     * 提交 TTS 任务（非阻塞）
     *
     * @param text             待转换文本
     * @param type             聚合类型
     * @param providerModelKey TTS 提供者和模型
     * @param options          TTS 选项
     * @return 任务序号
     */
    public int submitTask(String text, TextAggregator.AggregationType type,
                          String providerModelKey, TTSOptions options) {
        if (!running.get()) {
            log.warn("处理器已关闭，拒绝新任务");
            return -1;
        }

        int sequence = sequenceCounter.getAndIncrement();
        TTSTask task = new TTSTask(sequence, text, type, providerModelKey, options);
        ttsExecutor.submit(() -> executeTask(task));
        return sequence;
    }

    /**
     * 执行 TTS 任务
     */
    private void executeTask(TTSTask task) {
        if (!running.get()) {
            return;
        }
        TTSResult result;
        try {
            log.debug("开始执行 TTS 任务: seq={}, text={}", task.getSequence(), task.getText());

            // 调用 TTS 获取音频流
            List<TTSAudio> audioChunks = ttsManager.textToSpeechStream(
                    task.getProviderModelKey(),
                    task.getText(),
                    task.getOptions()
            ).collectList().block();

            if (audioChunks == null || audioChunks.isEmpty()) {
                log.warn("TTS 返回空音频: seq={}, text={}", task.getSequence(), task.getText());
                result = TTSResult.failure(task.getSequence(), task.getText(), "TTS 返回空音频");
            } else {
                // 收集 PCM 数据
                ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
                for (TTSAudio audio : audioChunks) {
                    if (!running.get()) {
                        return; // 被中断
                    }
                    if (audio.getAudioData() != null) {
                        pcmBuffer.write(audio.getAudioData());
                    }
                }

                byte[] pcmData = pcmBuffer.toByteArray();
                if (pcmData.length == 0) {
                    result = TTSResult.failure(task.getSequence(), task.getText(), "PCM 数据为空");
                } else {
                    // 转换为 OPUS
                    byte[] opusData = opusCodec.encodePcmToOpus(pcmData);
                    result = TTSResult.success(task.getSequence(), task.getText(), pcmData, opusData);
                }
            }
        } catch (Exception e) {
            log.error("TTS 任务执行失败: seq={}, text={}", task.getSequence(), task.getText(), e);
            result = TTSResult.failure(task.getSequence(), task.getText(), e.getMessage());
        }

        // 将结果放入待处理映射
        pendingResults.put(result.getSequence(), result);

        // 尝试按序发送结果
        tryDispatchResults();
    }

    /**
     * 尝试按序分发结果到队列
     * <p>
     * 只有当期望序号的结果就绪时才发送到队列，确保播放顺序。
     * 如果后面的任务先完成，会被缓存在 pendingResults 中等待。
     */
    private synchronized void tryDispatchResults() {
        while (true) {
            int expected = expectedSequence.get();
            TTSResult result = pendingResults.remove(expected);

            if (result == null) {
                // 期望的结果还没准备好
                int pendingCount = pendingResults.size();
                if (pendingCount > 0) {
                    log.debug("等待序号 {} 的 TTS 结果，当前有 {} 个提前完成的任务在等待",
                            expected, pendingCount);
                }
                break;
            }

            try {
                // 放入结果队列供消费者处理
                resultQueue.put(result);
                expectedSequence.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("结果入队被中断: seq={}", result.getSequence());
                break;
            }
        }
    }

    /**
     * 消费者线程：按序从队列取结果并播放
     */
    private void consumeResults() {
        while (running.get() || !resultQueue.isEmpty()) {
            try {
                // 等待结果（带超时，便于检查退出条件）
                TTSResult result = resultQueue.poll(100, TimeUnit.MILLISECONDS);

                if (result == null) {
                    // 检查是否已完成且队列为空
                    if (completed.get() && resultQueue.isEmpty() &&
                            pendingResults.isEmpty() &&
                            expectedSequence.get() >= sequenceCounter.get()) {
                        log.debug("所有任务已处理完成");
                        break;
                    }
                    continue;
                }

                if (result.isSuccess()) {
                    // 保存音频（PCM 和 OPUS）
                    if (audioSaver != null) {
                        audioSaver.accept(result.getPcmData(), result.getOpusData());
                    }

                    // 发送音频
                    sendAudioWithTiming(result.getOpusData());
                } else {
                    log.warn("TTS 失败，跳过: seq={}, error={}",
                            result.getSequence(), result.getErrorMessage());
                    if (errorHandler != null) {
                        errorHandler.accept(result.getErrorMessage());
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("消费者线程被中断");
                break;
            } catch (Exception e) {
                log.error("消费者处理异常", e);
            }
        }
        completionLatch.countDown();
    }

    /**
     * 发送 OPUS 音频数据（带时机控制）
     */
    private void sendAudioWithTiming(byte[] opusData) {
        if (opusData == null || opusData.length == 0) {
            return;
        }

        // 按 OPUS 帧发送，每帧格式：[2字节长度头][帧数据]
        int offset = 0;
        int frameCount = 0;
        int totalFrames = countOpusFrames(opusData);

        while (offset + 2 <= opusData.length && running.get()) {
            // 读取帧长度（2字节小端序）
            int frameLen = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
            int totalFrameSize = 2 + frameLen;

            if (offset + totalFrameSize > opusData.length) {
                log.warn("OPUS 帧数据不完整: offset={}, frameLen={}, dataLen={}",
                        offset, frameLen, opusData.length);
                break;
            }

            // 提取完整帧
            byte[] frame = new byte[totalFrameSize];
            System.arraycopy(opusData, offset, frame, 0, totalFrameSize);

            // 发送帧
            frameCount++;
            // finished 表示“本段 TTS 的最后一帧”
            boolean isLastFrame = (offset + totalFrameSize >= opusData.length);
            audioSender.accept(frame, isLastFrame);

            // 简单的时机控制：每帧 20ms，但不完全阻塞以保持响应性
            // 实际时机控制可以更精细
            if (!isLastFrame) {
                try {
                    Thread.sleep(18); // 稍微短于20ms，留出处理余量
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            offset += totalFrameSize;
        }
    }

    /**
     * 计算 OPUS 帧数
     */
    private int countOpusFrames(byte[] opusData) {
        int count = 0;
        int offset = 0;
        while (offset + 2 <= opusData.length) {
            int frameLen = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
            offset += 2 + frameLen;
            count++;
        }
        return count;
    }

    /**
     * 标记所有任务已提交完成
     * <p>
     * 调用后不再接受新任务，等待现有任务处理完成
     */
    public void complete() {
        completed.set(true);

        // 尝试分发剩余结果
        tryDispatchResults();
    }

    /**
     * 等待所有任务处理完成
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 是否在超时前完成
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completionLatch.await(timeout, unit);
    }

    /**
     * 中断处理（立即停止）
     */
    public void interrupt() {
        log.info("中断并发 TTS 处理器");
        running.set(false);
        completed.set(true);
        pendingResults.clear();
        resultQueue.clear();
        completionLatch.countDown();
    }

    /**
     * 获取待处理任务数
     */
    public int getPendingCount() {
        return sequenceCounter.get() - expectedSequence.get();
    }

    /**
     * 是否所有任务已完成
     */
    public boolean isAllCompleted() {
        return completed.get() &&
                pendingResults.isEmpty() &&
                resultQueue.isEmpty() &&
                expectedSequence.get() >= sequenceCounter.get();
    }

    @Override
    public void close() {
        log.info("关闭并发 TTS 处理器");
        running.set(false);
        completed.set(true);

        // 关闭线程池
        ttsExecutor.shutdown();
        try {
            if (!ttsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ttsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ttsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 等待消费者线程退出
        try {
            consumerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        completionLatch.countDown();
    }
}
