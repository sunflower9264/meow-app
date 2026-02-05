package com.miaomiao.assistant.websocket.service.pipeline;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Frame 流式处理管道
 * <p>
 * 移植自 Pipecat 的 Pipeline 概念，将多个 FrameProcessor 连接成一个处理流。
 * 支持流式处理、中断控制、错误处理等功能。
 * <p>
 * 参考: pipecat-main/src/pipecat/pipeline/pipeline.py
 *
 * @author Pipecat移植
 */
@Slf4j
public class FramePipeline {

    /**
     * 管道配置
     */
    public static class PipelineConfig {
        private int bufferSize = 256;
        private boolean enableTimingControl = true;

        public static PipelineConfig create() {
            return new PipelineConfig();
        }

        public PipelineConfig bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        public PipelineConfig enableTimingControl(boolean enable) {
            this.enableTimingControl = enable;
            return this;
        }
    }

    private final String sessionId;
    private final PipelineConfig config;
    private final List<FrameProcessor> processors;
    private final Sinks.Many<Frame> frameSink;
    private final FrameProcessor.ProcessingContext context;
    private final AtomicBoolean running;

    /**
     * 构造函数
     *
     * @param sessionId 会话ID
     * @param config    管道配置
     */
    public FramePipeline(String sessionId, PipelineConfig config) {
        this.sessionId = sessionId;
        this.config = config;
        this.processors = new ArrayList<>();
        this.frameSink = Sinks.many().unicast().onBackpressureBuffer();
        this.context = new FrameProcessor.ProcessingContext(sessionId);
        this.running = new AtomicBoolean(false);
    }

    /**
     * 构造函数（使用默认配置）
     *
     * @param sessionId 会话ID
     */
    public FramePipeline(String sessionId) {
        this(sessionId, PipelineConfig.create());
    }

    /**
     * 添加处理器
     *
     * @param processor Frame 处理器
     * @return this（链式调用）
     */
    public FramePipeline addProcessor(FrameProcessor processor) {
        processors.add(processor);
        log.debug("会话 {} 添加处理器: {}", sessionId, processor.getClass().getSimpleName());
        return this;
    }

    /**
     * 启动管道
     *
     * @return Frame 流
     */
    public Flux<Frame> start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("会话 {} 管道已在运行", sessionId);
            return Flux.empty();
        }

        log.info("会话 {} 启动 Frame 管道，处理器数量: {}", sessionId, processors.size());

        return frameSink.asFlux()
                .takeWhile(frame -> !context.isInterrupted())
                .doOnNext(frame -> log.trace("会话 {} 处理 Frame: {}", sessionId, frame))
                .map(frame -> processFrame(frame))
                .doOnComplete(() -> {
                    log.debug("会话 {} Frame 管道完成", sessionId);
                    running.set(false);
                })
                .doOnError(error -> {
                    log.error("会话 {} Frame 管道错误", sessionId, error);
                    running.set(false);
                });
    }

    /**
     * 推送 Frame 到管道
     *
     * @param frame 要推送的 Frame
     */
    public void pushFrame(Frame frame) {
        if (!running.get()) {
            log.warn("会话 {} 管道未运行，无法推送 Frame", sessionId);
            return;
        }

        Sinks.EmitResult result = frameSink.tryEmitNext(frame);
        if (result != Sinks.EmitResult.OK) {
            log.warn("会话 {} 推送 Frame 失败: {}", sessionId, result);
        }
    }

    /**
     * 中断管道处理
     */
    public void interrupt() {
        context.interrupt();
        log.info("会话 {} Frame 管道已中断", sessionId);
    }

    /**
     * 重置管道状态
     */
    public void reset() {
        context.reset();
        log.debug("会话 {} Frame 管道状态已重置", sessionId);
    }

    /**
     * 停止管道
     */
    public void stop() {
        interrupt();
        frameSink.tryEmitComplete();
        running.set(false);
        log.info("会话 {} Frame 管道已停止", sessionId);
    }

    /**
     * 检查管道是否运行中
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 处理单个 Frame
     */
    private Frame processFrame(Frame frame) {
        if (context.isInterrupted()) {
            return frame;
        }

        Frame currentFrame = frame;
        for (FrameProcessor processor : processors) {
            try {
                processor.processFrame(currentFrame, context);
                if (context.isInterrupted()) {
                    break;
                }
            } catch (FrameProcessor.FrameProcessingException e) {
                log.error("会话 {} 处理 Frame 失败: {}", sessionId, frame, e);
                // 发送错误帧
                return new Frames.MessageFrame("error", e.getMessage());
            }
        }

        return currentFrame;
    }

    /**
     * 获取处理上下文
     */
    public FrameProcessor.ProcessingContext getContext() {
        return context;
    }

    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取处理器列表
     */
    public List<FrameProcessor> getProcessors() {
        return new ArrayList<>(processors);
    }

    /**
     * 获取管道配置
     */
    public PipelineConfig getConfig() {
        return config;
    }
}
