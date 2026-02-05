package com.miaomiao.assistant.websocket.service.pipeline;

import lombok.extern.slf4j.Slf4j;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;

import java.io.InputStream;
import java.util.Set;

/**
 * 句子检测工具类
 * <p>
 * 移植自 Pipecat 的 match_endofsentence 函数，使用 OpenNLP（Java 版 NLTK）
 * 进行智能句子边界检测，正确处理 "Mr." "Dr." 等缩写词。
 * <p>
 * 参考: pipecat-main/src/pipecat/utils/string.py:match_endofsentence()
 *
 * @author Pipecat移植
 */
@Slf4j
public class SentenceDetector {

    private static volatile SentenceDetector instance;
    private SentenceDetectorME sentenceDetector;
    private boolean modelLoaded = false;

    /**
     * 句子结束标点符号集合（拉丁语系）
     * 这些需要 NLP 模型来消歧（比如 "Mr." 不是句子结尾）
     */
    private static final Set<Character> LATIN_SENTENCE_ENDING_PUNCTUATION = Set.of(
            '.', '!', '?', ';', '…'
    );

    /**
     * 非拉丁语系的明确句子结束标点（不需要消歧）
     * 中文、日文、韩文等使用的标点符号
     */
    private static final Set<Character> UNAMBIGUOUS_SENTENCE_ENDING_PUNCTUATION = Set.of(
            '。', '？', '！', '；', '．', '｡',  // 东亚标点
            '।', '॥',                          // 梵文/印地语
            '؟', '؛', '۔',                     // 阿拉伯语/乌尔都语
            '၊', '။',                          // 缅甸语
            '។', '៕',                          // 高棉语
            '།', '༎',                          // 藏语
            '։', '՜', '՞',                     // 亚美尼亚语
            '።', '፧', '፨'                      // 埃塞俄比亚语
    );

    /**
     * 所有句子结束标点（用于快速检查）
     */
    private static final Set<Character> ALL_SENTENCE_ENDING_PUNCTUATION;

    static {
        ALL_SENTENCE_ENDING_PUNCTUATION = new java.util.HashSet<>();
        ALL_SENTENCE_ENDING_PUNCTUATION.addAll(LATIN_SENTENCE_ENDING_PUNCTUATION);
        ALL_SENTENCE_ENDING_PUNCTUATION.addAll(UNAMBIGUOUS_SENTENCE_ENDING_PUNCTUATION);
    }

    private SentenceDetector() {
        loadModel();
    }

    /**
     * 获取单例实例
     */
    public static SentenceDetector getInstance() {
        if (instance == null) {
            synchronized (SentenceDetector.class) {
                if (instance == null) {
                    instance = new SentenceDetector();
                }
            }
        }
        return instance;
    }

    /**
     * 加载 OpenNLP 英文句子检测模型
     */
    private void loadModel() {
        try {
            // 尝试从 classpath 加载模型
            InputStream modelIn = getClass().getResourceAsStream("/models/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin");
            if (modelIn == null) {
                // 尝试备用路径
                modelIn = getClass().getResourceAsStream("/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin");
            }
            if (modelIn != null) {
                SentenceModel model = new SentenceModel(modelIn);
                sentenceDetector = new SentenceDetectorME(model);
                modelLoaded = true;
                log.info("OpenNLP 句子检测模型加载成功");
                modelIn.close();
            } else {
                log.warn("未找到 OpenNLP 句子检测模型，将使用简单规则检测");
            }
        } catch (Exception e) {
            log.warn("加载 OpenNLP 模型失败，将使用简单规则检测: {}", e.getMessage());
        }
    }

    /**
     * 检测文本中第一个完整句子的结束位置
     * <p>
     * 移植自 Pipecat 的 match_endofsentence()，逻辑：
     * 1. 使用 OpenNLP 检测句子边界
     * 2. 如果只有一个句子且等于整个文本，验证是否以句子结束标点结尾
     * 3. 对于非拉丁语系标点，直接扫描（不需要 NLP 消歧）
     *
     * @param text 输入文本
     * @return 句子结束位置（0 表示未找到完整句子）
     */
    public int matchEndOfSentence(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        text = text.stripTrailing();
        if (text.isEmpty()) {
            return 0;
        }

        // 如果模型可用，使用 OpenNLP 检测
        if (modelLoaded && sentenceDetector != null) {
            return matchWithOpenNLP(text);
        }

        // 降级到简单规则检测
        return matchWithSimpleRules(text);
    }

    /**
     * 使用 OpenNLP 进行句子检测
     */
    private int matchWithOpenNLP(String text) {
        String[] sentences = sentenceDetector.sentDetect(text);

        if (sentences == null || sentences.length == 0) {
            return 0;
        }

        String firstSentence = sentences[0];

        // 如果只有一个句子且等于整个文本
        if (sentences.length == 1 && firstSentence.equals(text)) {
            // 验证是否以句子结束标点结尾
            char lastChar = text.charAt(text.length() - 1);
            if (ALL_SENTENCE_ENDING_PUNCTUATION.contains(lastChar)) {
                return text.length();
            }

            // 回退：扫描非拉丁语系的明确句子结束标点
            for (int i = 0; i < text.length(); i++) {
                if (UNAMBIGUOUS_SENTENCE_ENDING_PUNCTUATION.contains(text.charAt(i))) {
                    return i + 1;
                }
            }
            return 0;
        }

        // 有多个句子，第一个一定是完整的
        if (sentences.length > 1) {
            return firstSentence.length();
        }

        return 0;
    }

    /**
     * 使用简单规则进行句子检测（降级方案）
     */
    private int matchWithSimpleRules(String text) {
        // 扫描非拉丁语系的明确句子结束标点
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (UNAMBIGUOUS_SENTENCE_ENDING_PUNCTUATION.contains(ch)) {
                return i + 1;
            }
        }

        // 对于拉丁语系标点，只在文本末尾检查
        char lastChar = text.charAt(text.length() - 1);
        if (LATIN_SENTENCE_ENDING_PUNCTUATION.contains(lastChar)) {
            return text.length();
        }

        return 0;
    }

    /**
     * 检查字符是否为句子结束标点
     */
    public boolean isSentenceEndingPunctuation(char ch) {
        return ALL_SENTENCE_ENDING_PUNCTUATION.contains(ch);
    }

    /**
     * 检查是否为拉丁语系标点（需要 NLP 消歧）
     */
    public boolean isLatinPunctuation(char ch) {
        return LATIN_SENTENCE_ENDING_PUNCTUATION.contains(ch);
    }

    /**
     * 模型是否已加载
     */
    public boolean isModelLoaded() {
        return modelLoaded;
    }
}
