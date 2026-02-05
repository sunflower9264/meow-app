package com.miaomiao.assistant.config;

import com.miaomiao.assistant.websocket.service.pipeline.SentenceDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 模型预热服务
 * <p>
 * 在应用启动时预加载 OpenNLP 句子检测模型，避免第一次请求时的冷启动延迟。
 */
@Slf4j
@Component
public class ModelWarmupService {

    /**
     * 应用启动后预加载模型
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        log.info("开始预加载模型...");
        long startTime = System.currentTimeMillis();

        try {
            // 预加载 OpenNLP 句子检测模型
            warmupSentenceDetector();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("模型预加载完成，耗时 {}ms", elapsed);
        } catch (Exception e) {
            log.warn("模型预加载失败，首次请求可能较慢: {}", e.getMessage());
        }
    }

    /**
     * 预加载 OpenNLP 句子检测模型
     */
    private void warmupSentenceDetector() {
        try {
            log.info("预加载 OpenNLP 句子检测模型 (opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin)...");
            long start = System.currentTimeMillis();

            // 调用 getInstance() 会触发模型加载
            SentenceDetector detector = SentenceDetector.getInstance();

            // 进行一次测试调用，确保模型完全初始化
            detector.matchEndOfSentence("Hello world.");

            long elapsed = System.currentTimeMillis() - start;
            log.info("OpenNLP 句子检测模型预加载完成 ({}ms)", elapsed);
        } catch (Exception e) {
            log.warn("OpenNLP 句子检测模型预加载失败: {}", e.getMessage());
        }
    }
}
