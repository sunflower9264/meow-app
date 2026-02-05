package com.miaomiao.assistant.websocket.service.pipeline;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * Markdown 文本过滤器
 * <p>
 * 用于清理 LLM 输出中的 Markdown 格式，使其适合 TTS 朗读。
 * <p>
 * 处理内容包括：
 * 1. 代码块（```code```）- 完全移除或替换为提示语
 * 2. 行内代码（`code`）- 移除反引号
 * 3. 标题标记（# ## ###）- 移除
 * 4. 粗体/斜体（**text** *text*）- 移除标记保留文本
 * 5. 链接（[text](url)）- 保留文本移除 URL
 * 6. 图片（![alt](url)）- 替换为提示语
 * 7. 列表标记（- * 1.）- 移除
 * 8. 引用（>）- 移除
 * 9. 水平线（---）- 移除
 */
@Slf4j
public class MarkdownTextFilter {

    private static volatile MarkdownTextFilter instance;

    // 代码块模式（多行）
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```[\\s\\S]*?```",
            Pattern.MULTILINE
    );

    // 行内代码
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");

    // 标题（# ## ### 等）
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+", Pattern.MULTILINE);

    // 粗体 **text** 或 __text__
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");

    // 斜体 *text* 或 _text_（注意不要和粗体冲突）
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)");

    // 链接 [text](url)
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");

    // 图片 ![alt](url)
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)]\\([^)]+\\)");

    // 无序列表 - * +
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^\\s*[-*+]\\s+", Pattern.MULTILINE);

    // 有序列表 1. 2. 等
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\s*\\d+\\.\\s+", Pattern.MULTILINE);

    // 引用 >
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^>\\s*", Pattern.MULTILINE);

    // 水平线 --- *** ___
    private static final Pattern HORIZONTAL_RULE_PATTERN = Pattern.compile("^[-*_]{3,}\\s*$", Pattern.MULTILINE);

    // 删除线 ~~text~~
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.+?)~~");

    // 多个连续空行
    private static final Pattern MULTIPLE_NEWLINES_PATTERN = Pattern.compile("\n{3,}");

    // 多个连续空格
    private static final Pattern MULTIPLE_SPACES_PATTERN = Pattern.compile(" {2,}");

    private MarkdownTextFilter() {
    }

    /**
     * 获取单例实例
     */
    public static MarkdownTextFilter getInstance() {
        if (instance == null) {
            synchronized (MarkdownTextFilter.class) {
                if (instance == null) {
                    instance = new MarkdownTextFilter();
                }
            }
        }
        return instance;
    }

    /**
     * 过滤 Markdown 格式，返回适合 TTS 的纯文本
     *
     * @param text 原始文本（可能包含 Markdown）
     * @return 过滤后的纯文本
     */
    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;

        // 1. 移除代码块（替换为提示语）
        result = CODE_BLOCK_PATTERN.matcher(result).replaceAll(" 代码已省略 ");

        // 2. 移除行内代码的反引号
        result = INLINE_CODE_PATTERN.matcher(result).replaceAll("$1");

        // 3. 移除图片（替换为提示语）
        result = IMAGE_PATTERN.matcher(result).replaceAll(" 图片 ");

        // 4. 处理链接（保留文本）
        result = LINK_PATTERN.matcher(result).replaceAll("$1");

        // 5. 移除标题标记
        result = HEADING_PATTERN.matcher(result).replaceAll("");

        // 6. 移除粗体标记
        result = BOLD_PATTERN.matcher(result).replaceAll("$1$2");

        // 7. 移除斜体标记
        result = ITALIC_PATTERN.matcher(result).replaceAll("$1$2");

        // 8. 移除删除线
        result = STRIKETHROUGH_PATTERN.matcher(result).replaceAll("$1");

        // 9. 移除列表标记
        result = UNORDERED_LIST_PATTERN.matcher(result).replaceAll("");
        result = ORDERED_LIST_PATTERN.matcher(result).replaceAll("");

        // 10. 移除引用标记
        result = BLOCKQUOTE_PATTERN.matcher(result).replaceAll("");

        // 11. 移除水平线
        result = HORIZONTAL_RULE_PATTERN.matcher(result).replaceAll("");

        // 12. 清理多余空白
        result = MULTIPLE_NEWLINES_PATTERN.matcher(result).replaceAll("\n\n");
        result = MULTIPLE_SPACES_PATTERN.matcher(result).replaceAll(" ");

        // 13. 清理首尾空白
        return result.trim();
    }

    /**
     * 检查文本是否包含代码块
     */
    public boolean containsCodeBlock(String text) {
        return text != null && CODE_BLOCK_PATTERN.matcher(text).find();
    }

    /**
     * 检查文本是否主要是代码（代码占比超过阈值）
     *
     * @param text      文本
     * @param threshold 阈值（0-1）
     * @return 是否主要是代码
     */
    public boolean isMainlyCode(String text, double threshold) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        String withoutCode = CODE_BLOCK_PATTERN.matcher(text).replaceAll("");
        double codeRatio = 1.0 - ((double) withoutCode.length() / text.length());
        return codeRatio > threshold;
    }
}
