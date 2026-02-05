package com.miaomiao.assistant.websocket.session;

import com.miaomiao.assistant.model.llm.AppChatMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket会话状态管理
 * 管理单个WebSocket连接的状态，包括音频缓冲、对话历史等
 */
@Slf4j
public class SessionState {

    @Getter
    private final WebSocketSession session;

    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    private final List<AppChatMessage> conversationHistory = new CopyOnWriteArrayList<>();

    /**
     * 当前活跃的流订阅，用于取消操作
     */
    private final AtomicReference<Disposable> activeDisposable = new AtomicReference<>();

    @Getter
    private volatile boolean aborted = false;

    /**
     * 性能指标跟踪
     */
    @Getter
    private final PerformanceMetrics performanceMetrics;

    public SessionState(WebSocketSession session) {
        this.session = session;
        this.performanceMetrics = new PerformanceMetrics(session.getId());
    }

    /**
     * 累积音频数据到缓冲区
     */
    public synchronized void accumulateAudio(byte[] data) {
        try {
            audioBuffer.write(data);
        } catch (IOException e) {
            throw new RuntimeException("累积音频失败", e);
        }
    }

    /**
     * 获取并清空音频缓冲区
     */
    public synchronized byte[] getAndClearAudioBuffer() {
        byte[] data = audioBuffer.toByteArray();
        audioBuffer.reset();
        return data;
    }

    /**
     * 获取对话历史的副本
     */
    public List<AppChatMessage> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 添加消息到对话历史
     */
    public void addMessage(String role, String content) {
        conversationHistory.add(new AppChatMessage(role, content));
        // 保持历史在合理大小
        if (conversationHistory.size() > 50) {
            conversationHistory.subList(0, 10).clear();
        }
    }

    /**
     * 设置当前活跃的流订阅
     */
    public void setActiveDisposable(Disposable disposable) {
        // 先取消之前的订阅
        Disposable previous = activeDisposable.getAndSet(disposable);
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }
    }

    /**
     * 中止当前操作，取消活跃的流订阅
     */
    public void abort() {
        this.aborted = true;
        Disposable disposable = activeDisposable.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
            log.info("已取消活跃的流订阅，会话: {}", getSessionId());
        }
    }

    /**
     * 清除中止状态，准备新的对话
     */
    public void resetAborted() {
        this.aborted = false;
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        abort();  // 先取消所有活跃流
        try {
            audioBuffer.close();
        } catch (IOException e) {
            // 忽略
        }
        // 关闭性能指标的文件保存线程池
        performanceMetrics.shutdown();
    }

    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return session.getId();
    }
}
