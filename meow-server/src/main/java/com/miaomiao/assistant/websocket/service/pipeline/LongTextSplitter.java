package com.miaomiao.assistant.websocket.service.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 智能长文本拆分处理器
 * <p>
 * 使用 SentenceDetector 进行智能句子边界检测，确保：
 * 1. 句子不在中间被截断（尤其是英文的 "Mr.", "Dr." 等缩写）
 * 2. 每个片段控制在合理长度以获得更快的 TTS 响应
 * 3. 中英文混合文本都能正确处理
 * <p>
 * 拆分策略（优先级从高到低）：
 * 1. 使用 SentenceDetector 检测完整句子边界
 * 2. 如果单个句子超长，在子句标点（逗号等）处拆分
 * 3. 最后使用词边界/空格拆分
 */
@Slf4j
public class LongTextSplitter implements TextPreProcessor {

    /**
     * 默认最大长度：80 字符
     * <p>
     * 选择 80 的理由：
     * - 中文：约 40 个汉字，一个正常句子的长度
     * - 英文：约 15-20 个单词，一个正常句子的长度
     * - TTS 响应更快，减少首句延迟
     * - 并发任务处理时间更均衡
     */
    private static final int DEFAULT_MAX_LENGTH = 80;

    /**
     * 句子结束标点（优先拆分点）
     */
    private static final Set<Character> SENTENCE_END_PUNCTUATIONS = Set.of(
            '。', '？', '！', '；', '.', '?', '!', ';', '…'
    );

    /**
     * 子句标点（次优先拆分点）
     */
    private static final Set<Character> CLAUSE_PUNCTUATIONS = Set.of(
            '，', ',', '、', '：', ':', '—', '-', '）', ')', '」', '"', '\''
    );

    private final int maxLength;
    private final SentenceDetector sentenceDetector;

    public LongTextSplitter() {
        this(DEFAULT_MAX_LENGTH);
    }

    public LongTextSplitter(int maxLength) {
        this.maxLength = Math.max(20, maxLength); // 最小 20 字符
        this.sentenceDetector = SentenceDetector.getInstance();
    }

    @Override
    public List<String> process(List<String> texts) {
        List<String> result = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            result.addAll(smartSplit(text));
        }
        return result;
    }

    @Override
    public int getOrder() {
        return 200; // MD过滤后执行
    }

    /**
     * 智能拆分文本
     * <p>
     * 策略：
     * 1. 先使用 SentenceDetector 提取完整句子
     * 2. 如果句子超长，递归拆分
     */
    private List<String> smartSplit(String text) {
        List<String> result = new ArrayList<>();
        String remaining = text.trim();

        while (!remaining.isEmpty()) {
            // 使用 SentenceDetector 检测第一个完整句子
            int sentenceEnd = sentenceDetector.matchEndOfSentence(remaining);

            if (sentenceEnd > 0) {
                // 找到完整句子
                String sentence = remaining.substring(0, sentenceEnd).trim();
                remaining = remaining.substring(sentenceEnd).trim();

                if (sentence.length() <= maxLength) {
                    // 句子在限制内，直接添加
                    result.add(sentence);
                } else {
                    // 句子超长，需要再拆分
                    result.addAll(splitLongSentence(sentence));
                }
            } else {
                // 没有检测到完整句子（可能文本未结束或无标点）
                if (remaining.length() <= maxLength) {
                    // 剩余文本在限制内，直接添加
                    result.add(remaining);
                    break;
                } else {
                    // 剩余文本超长，尝试在限制范围内找分割点
                    int splitPos = findBestSplitPosition(remaining, maxLength);
                    String chunk = remaining.substring(0, splitPos).trim();
                    remaining = remaining.substring(splitPos).trim();

                    if (!chunk.isEmpty()) {
                        result.add(chunk);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 拆分超长句子
     * <p>
     * 当单个句子超过最大长度时，按以下优先级拆分：
     * 1. 子句标点（逗号等）
     * 2. 空格（主要针对英文）
     * 3. 强制拆分
     */
    private List<String> splitLongSentence(String sentence) {
        List<String> result = new ArrayList<>();
        String remaining = sentence;

        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLength) {
                result.add(remaining);
                break;
            }

            int splitPos = findBestSplitPosition(remaining, maxLength);
            String chunk = remaining.substring(0, splitPos).trim();
            remaining = remaining.substring(splitPos).trim();

            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
        }

        return result;
    }

    /**
     * 在指定范围内找到最佳拆分位置
     *
     * @param text      文本
     * @param maxLength 最大长度
     * @return 拆分位置
     */
    private int findBestSplitPosition(String text, int maxLength) {
        int end = Math.min(maxLength, text.length());

        // 1. 优先在句子结束标点处拆分
        int pos = findLastPunctuationPosition(text, end, SENTENCE_END_PUNCTUATIONS);
        if (pos > 0) {
            return pos + 1;
        }

        // 2. 在子句标点处拆分
        pos = findLastPunctuationPosition(text, end, CLAUSE_PUNCTUATIONS);
        if (pos > 0) {
            return pos + 1;
        }

        // 3. 在空格处拆分（英文单词边界）
        pos = findLastSpacePosition(text, end);
        if (pos > 0) {
            return pos + 1;
        }

        // 4. 强制在最大长度处拆分（最后手段）
        // 尝试不拆分在闭合引号、括号等之前
        return end;
    }

    /**
     * 在指定范围内查找最后一个标点位置
     */
    private int findLastPunctuationPosition(String text, int end, Set<Character> punctuations) {
        // 从 end-1 开始向前搜索，留出一些空间
        int minPos = Math.max(0, end / 3); // 至少保留 1/3 的内容
        for (int i = end - 1; i >= minPos; i--) {
            if (punctuations.contains(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 在指定范围内查找最后一个空格位置
     */
    private int findLastSpacePosition(String text, int end) {
        int minPos = Math.max(0, end / 3);
        for (int i = end - 1; i >= minPos; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
