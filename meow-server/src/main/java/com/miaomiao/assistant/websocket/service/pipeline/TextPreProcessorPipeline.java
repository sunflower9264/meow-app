package com.miaomiao.assistant.websocket.service.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TTS 文本预处理管道
 * <p>
 * 按照处理器优先级顺序执行所有预处理器。
 */
public class TextPreProcessorPipeline {

    private static volatile TextPreProcessorPipeline instance;

    private final List<TextPreProcessor> processors;

    private TextPreProcessorPipeline() {
        this.processors = new ArrayList<>();
        // 注册默认处理器
        processors.add(new MarkdownFilterProcessor());
        processors.add(new LongTextSplitter());
        // 按优先级排序
        processors.sort(Comparator.comparingInt(TextPreProcessor::getOrder));
    }

    public static TextPreProcessorPipeline getInstance() {
        if (instance == null) {
            synchronized (TextPreProcessorPipeline.class) {
                if (instance == null) {
                    instance = new TextPreProcessorPipeline();
                }
            }
        }
        return instance;
    }

    /**
     * 处理文本
     *
     * @param text 原始文本
     * @return 处理后的文本列表（可能因拆分产生多个）
     */
    public List<String> process(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> texts = List.of(text);
        for (TextPreProcessor processor : processors) {
            texts = processor.process(texts);
            if (texts.isEmpty()) {
                break;
            }
        }
        return texts;
    }

    /**
     * Markdown 过滤处理器（包装现有的 MarkdownTextFilter）
     */
    private static class MarkdownFilterProcessor implements TextPreProcessor {

        private final MarkdownTextFilter filter = MarkdownTextFilter.getInstance();

        @Override
        public List<String> process(List<String> texts) {
            List<String> result = new ArrayList<>();
            for (String text : texts) {
                String filtered = filter.filter(text);
                if (filtered != null && !filtered.trim().isEmpty()) {
                    result.add(filtered.trim());
                }
            }
            return result;
        }

        @Override
        public int getOrder() {
            return 100; // 最先执行
        }
    }
}
