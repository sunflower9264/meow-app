package com.miaomiao.assistant.websocket.service.pipeline;

/**
 * Frame 处理器接口
 * <p>
 * 移植自 Pipecat 的 FrameProcessor 概念，所有处理 Frame 的组件都需要实现这个接口。
 * <p>
 * 参考: pipecat-main/src/pipecat/processors/frame_processor.py
 *
 * @author Pipecat移植
 */
public interface FrameProcessor {

    /**
     * 处理 Frame
     *
     * @param frame   要处理的 Frame
     * @param context 处理上下文
     * @throws FrameProcessingException 处理异常
     */
    void processFrame(Frame frame, ProcessingContext context) throws FrameProcessingException;

    /**
     * 处理上下文
     */
    class ProcessingContext {
        private final String sessionId;
        private volatile boolean interrupted = false;

        public ProcessingContext(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public boolean isInterrupted() {
            return interrupted;
        }

        public void setInterrupted(boolean interrupted) {
            this.interrupted = interrupted;
        }

        public void interrupt() {
            this.interrupted = true;
        }

        public void reset() {
            this.interrupted = false;
        }
    }

    /**
     * Frame 处理异常
     */
    class FrameProcessingException extends Exception {
        public FrameProcessingException(String message) {
            super(message);
        }

        public FrameProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
