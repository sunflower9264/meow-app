package com.miaomiao.assistant.websocket.service.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 长文本拆分处理器
 * <p>
 * 将超过 TTS API 限制（1024字符）的文本拆分成多个片段。
 * 优先在标点符号处拆分，保证语义完整性。
 */
public class LongTextSplitter implements TextPreProcessor {

    private static final int DEFAULT_MAX_LENGTH = 1024;

    private static final Set<Character> SPLIT_PUNCTUATIONS = Set.of(
            '。', '？', '！', '；', '.', '?', '!', ';',
            '，', ',', '、', '：', ':'
    );

    private final int maxLength;

    public LongTextSplitter() {
        this(DEFAULT_MAX_LENGTH);
    }

    public LongTextSplitter(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public List<String> process(List<String> texts) {
        List<String> result = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (text.length() <= maxLength) {
                result.add(text);
            } else {
                result.addAll(split(text));
            }
        }
        return result;
    }

    @Override
    public int getOrder() {
        return 200; // MD过滤后执行
    }

    private List<String> split(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());

            if (end < text.length()) {
                int splitPos = findLastSplitPosition(text, start, end);
                if (splitPos > start) {
                    end = splitPos + 1;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }
            start = end;
        }

        return result;
    }

    private int findLastSplitPosition(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            if (SPLIT_PUNCTUATIONS.contains(text.charAt(i))) {
                return i;
            }
        }
        return end;
    }
}
