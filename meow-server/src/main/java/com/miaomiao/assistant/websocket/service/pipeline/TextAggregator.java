package com.miaomiao.assistant.websocket.service.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 文本聚合器
 * <p>
 * 移植自 Pipecat 的文本聚合机制，支持多种聚合策略来优化 LLM → TTS 的流式传输。
 * 通过智能分句，在低延迟和音频流畅性之间取得平衡。
 * <p>
 * 核心改进：
 * 1. 集成 OpenNLP 句子检测，正确处理 "Mr." "Dr." 等缩写
 * 2. 实现 lookahead 机制，检测到标点后等待下一个非空白字符再确认
 * <p>
 * 参考: pipecat-main/src/pipecat/utils/text/simple_text_aggregator.py
 *
 * @author Pipecat移植
 */
@Slf4j
public class TextAggregator {

    /**
     * 聚合策略枚举
     */
    public enum AggregationStrategy {
        /**
         * 按句子聚合（使用 OpenNLP 智能检测）
         * <p>
         * 使用 NLP 模型检测句子边界，正确处理缩写词
         */
        SENTENCE,

        /**
         * Token 级别聚合
         * <p>
         * 更激进，延迟更低，但可能导致句子被切断
         */
        TOKEN,

        /**
         * 混合策略
         * <p>
         * 首句使用更激进的标点（逗号、顿号），后续使用完整句子
         */
        HYBRID
    }

    /**
     * 聚合结果
     */
    public static class AggregateResult {
        private final String text;
        private final AggregationType type;
        private final boolean isFinal;

        public AggregateResult(String text, AggregationType type, boolean isFinal) {
            this.text = text;
            this.type = type;
            this.isFinal = isFinal;
        }

        public String getText() {
            return text;
        }

        public AggregationType getType() {
            return type;
        }

        public boolean isFinal() {
            return isFinal;
        }
    }

    /**
     * 聚合类型
     */
    public enum AggregationType {
        /**
         * 首句（更激进的标点）
         */
        FIRST_SENTENCE,

        /**
         * 完整句子
         */
        SENTENCE,

        /**
         * Token 级别
         */
        TOKEN,

        /**
         * 最终剩余文本
         */
        FINAL
    }

    /**
     * 聚合配置
     */
    public static class AggregationConfig {
        private AggregationStrategy strategy = AggregationStrategy.SENTENCE;
        private int maxTokenLength = 50;  // token 模式下的最大长度
        private boolean useNlpDetection = true;  // 是否使用 NLP 句子检测

        public static AggregationConfig create() {
            return new AggregationConfig();
        }

        public AggregationConfig strategy(AggregationStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public AggregationConfig maxTokenLength(int maxTokenLength) {
            this.maxTokenLength = maxTokenLength;
            return this;
        }

        public AggregationConfig useNlpDetection(boolean useNlpDetection) {
            this.useNlpDetection = useNlpDetection;
            return this;
        }
    }

    /**
     * 首句标点符号（更激进，更低延迟）
     * 用于 HYBRID 策略的首句检测
     */
    private static final Set<Character> FIRST_SENTENCE_PUNCTUATIONS = Set.of(
            '，', ',', '、', '。', '.', '？', '?', '！', '!', '；', ';', '：', ':', '~'
    );

    /**
     * 句子结束标点符号（用于 lookahead 触发）
     */
    private static final Set<Character> SENTENCE_ENDING_PUNCTUATION = Set.of(
            '.', '!', '?', ';', '…',
            '。', '？', '！', '；', '．', '｡'
    );

    private final AggregationConfig config;
    private final StringBuilder buffer;
    private final SentenceDetector sentenceDetector;
    private boolean firstSentenceSent = false;
    private boolean finalized = false;
    private boolean needsLookahead = false;  // 是否需要等待 lookahead

    /**
     * 构造函数
     *
     * @param config 聚合配置
     */
    public TextAggregator(AggregationConfig config) {
        this.config = config;
        this.buffer = new StringBuilder();
        this.sentenceDetector = config.useNlpDetection ? SentenceDetector.getInstance() : null;
    }

    /**
     * 构造函数（使用默认配置）
     */
    public TextAggregator() {
        this(AggregationConfig.create());
    }

    /**
     * 添加文本
     *
     * @param text 新增文本
     * @return 聚合结果列表（可能为空）
     */
    public List<AggregateResult> append(String text) {
        if (finalized) {
            log.warn("聚合器已结束，无法添加更多文本");
            return List.of();
        }

        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<AggregateResult> results = new ArrayList<>();

        // 逐字符处理（实现 lookahead 机制）
        for (char ch : text.toCharArray()) {
            buffer.append(ch);
            AggregateResult result = checkSentenceWithLookahead(ch);
            if (result != null) {
                results.add(result);
            }
        }

        return results;
    }

    /**
     * 带 lookahead 的句子检测
     * <p>
     * 移植自 Pipecat 的 _check_sentence_with_lookahead()：
     * 当检测到句子结束标点后，等待下一个非空白字符再调用 NLP 确认。
     * 这样可以消歧 "$29." vs "$29.95" 或 "Mr." vs "Mr. Wang"
     *
     * @param ch 刚添加的字符
     * @return 聚合结果（如果检测到完整句子）
     */
    private AggregateResult checkSentenceWithLookahead(char ch) {
        String bufferText = buffer.toString();

        // 如果需要 lookahead，检查是否收到了非空白字符
        if (needsLookahead) {
            if (!Character.isWhitespace(ch)) {
                // 收到非空白字符，现在调用 NLP 检测
                needsLookahead = false;
                return checkAndExtractSentence(bufferText);
            }
            // 还是空白字符，继续等待
            return null;
        }

        // 检查是否刚添加了句子结束标点
        if (bufferText.length() > 0) {
            char lastChar = bufferText.charAt(bufferText.length() - 1);

            // 如果是拉丁语系标点（需要消歧），启用 lookahead
            if (sentenceDetector != null && sentenceDetector.isLatinPunctuation(lastChar)) {
                needsLookahead = true;
                return null;
            }

            // 非拉丁语系标点（如中文句号），直接检测
            if (SENTENCE_ENDING_PUNCTUATION.contains(lastChar) &&
                    !sentenceDetector.isLatinPunctuation(lastChar)) {
                return checkAndExtractSentence(bufferText);
            }
        }

        return null;
    }

    /**
     * 检测并提取完整句子
     */
    private AggregateResult checkAndExtractSentence(String bufferText) {
        switch (config.strategy) {
            case TOKEN:
                return checkTokenStrategy(bufferText);

            case HYBRID:
                if (!firstSentenceSent) {
                    return checkFirstSentenceStrategy(bufferText);
                }
                return checkSentenceStrategy(bufferText);

            case SENTENCE:
            default:
                return checkSentenceStrategy(bufferText);
        }
    }

    /**
     * 句子策略：使用 NLP 检测句子边界
     */
    private AggregateResult checkSentenceStrategy(String bufferText) {
        int endPos = 0;

        if (sentenceDetector != null && config.useNlpDetection) {
            // 使用 OpenNLP 检测
            endPos = sentenceDetector.matchEndOfSentence(bufferText);
        } else {
            // 降级到简单标点检测
            endPos = findLastSentenceEnd(bufferText);
        }

        if (endPos > 0) {
            String sentenceText = bufferText.substring(0, endPos).trim();
            String remaining = bufferText.substring(endPos);

            buffer.setLength(0);
            buffer.append(remaining);

            return new AggregateResult(sentenceText, AggregationType.SENTENCE, false);
        }

        return null;
    }

    /**
     * 首句策略：使用更激进的标点
     */
    private AggregateResult checkFirstSentenceStrategy(String bufferText) {
        // 查找首句标点
        for (int i = 0; i < bufferText.length(); i++) {
            if (FIRST_SENTENCE_PUNCTUATIONS.contains(bufferText.charAt(i))) {
                String sentenceText = bufferText.substring(0, i + 1).trim();
                String remaining = bufferText.substring(i + 1);

                buffer.setLength(0);
                buffer.append(remaining);
                firstSentenceSent = true;

                return new AggregateResult(sentenceText, AggregationType.FIRST_SENTENCE, false);
            }
        }

        return null;
    }

    /**
     * Token 策略：按固定长度切分
     */
    private AggregateResult checkTokenStrategy(String bufferText) {
        if (buffer.length() >= config.maxTokenLength) {
            String tokenText = bufferText.substring(0, config.maxTokenLength);
            String remaining = bufferText.substring(config.maxTokenLength);

            buffer.setLength(0);
            buffer.append(remaining);

            return new AggregateResult(tokenText, AggregationType.TOKEN, false);
        }

        return null;
    }

    /**
     * 简单标点检测（降级方案）
     */
    private int findLastSentenceEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (SENTENCE_ENDING_PUNCTUATION.contains(text.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 完成聚合，返回剩余文本
     *
     * @return 最终聚合结果
     */
    public AggregateResult complete() {
        if (finalized) {
            return null;
        }

        finalized = true;

        String remaining = buffer.toString().trim();
        buffer.setLength(0);
        needsLookahead = false;

        if (remaining.isEmpty()) {
            return null;
        }

        return new AggregateResult(remaining, AggregationType.FINAL, true);
    }

    /**
     * 重置聚合器
     */
    public void reset() {
        buffer.setLength(0);
        firstSentenceSent = false;
        finalized = false;
        needsLookahead = false;
    }

    /**
     * 获取当前缓冲区内容
     */
    public String getBufferContent() {
        return buffer.toString();
    }

    /**
     * 检查缓冲区是否为空
     */
    public boolean isEmpty() {
        return buffer.length() == 0;
    }
}
