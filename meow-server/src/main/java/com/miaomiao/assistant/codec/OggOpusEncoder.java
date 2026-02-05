package com.miaomiao.assistant.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Ogg Opus 封装器
 * 按照 RFC 7845 标准将裸 Opus 帧封装成 Ogg Opus 格式
 *
 * 参考: https://datatracker.ietf.org/doc/html/rfc7845
 */
public class OggOpusEncoder {

    // Ogg 页面相关常量
    private static final String OGG_MAGIC = "OggS";
    private static final int OGG_SEGMENT_COUNT_MAX = 255;

    // Opus 头部相关常量
    private static final String OPUS_HEAD_MAGIC = "OpusHead";
    private static final String OPUS_TAGS_MAGIC = "OpusTags";
    private static final int OPUS_HEAD_VERSION = 1;
    private static final int CHANNEL_MAPPING_FAMILY = 0; // 单声道/立体声使用 VoIP 配置

    private final int sampleRate;
    private final int channels;
    private final int frameSize; // 每帧样本数 (如 480 = 20ms @ 24kHz)

    // 用于计算 granule position
    private long sampleCount = 0;
    private long pageSequence = 0;

    // 缓冲当前页面的数据
    private final ByteArrayOutputStream currentPageData = new ByteArrayOutputStream();

    /**
     * 创建 Ogg Opus 封装器
     *
     * @param sampleRate 采样率 (如 24000)
     * @param channels   声道数 (1 = 单声道, 2 = 立体声)
     * @param frameSize  每帧样本数 (如 480 = 20ms @ 24kHz)
     */
    public OggOpusEncoder(int sampleRate, int channels, int frameSize) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameSize = frameSize;
    }

    /**
     * 生成完整的 Ogg Opus 文件
     *
     * @param opusFrames 裸 Opus 帧数据数组（每帧已经是编码后的数据）
     * @return Ogg Opus 格式的完整文件数据
     */
    public byte[] encodeToOgg(byte[][] opusFrames) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // 写入 Identification Header
        output.write(createIdentificationHeaderPage());

        // 写入 Comment Header
        output.write(createCommentHeaderPage());

        // 写入音频数据页面
        for (byte[] frame : opusFrames) {
            addFrameToPage(frame);
        }
        // 刷新最后一个页面
        if (currentPageData.size() > 0) {
            output.write(createDataPage(currentPageData.toByteArray(), true));
            currentPageData.reset();
        }

        return output.toByteArray();
    }

    /**
     * 生成完整的 Ogg Opus 文件（带帧长度头的格式）
     *
     * @param opusDataWithLength 带帧长度头的 Opus 数据（[2字节长度][帧数据]...）
     * @return Ogg Opus 格式的完整文件数据
     */
    public byte[] encodeToOgg(byte[] opusDataWithLength) throws IOException {
        // 先解析出各个帧
        java.util.List<byte[]> frames = parseOpusFrames(opusDataWithLength);
        byte[][] frameArray = frames.toArray(new byte[0][]);
        return encodeToOgg(frameArray);
    }

    /**
     * 解析带长度头的 Opus 数据
     */
    private java.util.List<byte[]> parseOpusFrames(byte[] data) {
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        int offset = 0;

        while (offset + 2 <= data.length) {
            int frameLength = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            offset += 2;

            if (offset + frameLength > data.length) {
                break;
            }

            byte[] frame = new byte[frameLength];
            System.arraycopy(data, offset, frame, 0, frameLength);
            frames.add(frame);
            offset += frameLength;
        }

        return frames;
    }

    /**
     * 创建 Identification Header 页面
     */
    private byte[] createIdentificationHeaderPage() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();

        // OpusHead 魔数
        header.write(OPUS_HEAD_MAGIC.getBytes());

        // 版本
        header.write(OPUS_HEAD_VERSION);

        // 声道数
        header.write(channels);

        // Preskip (跳过样本数，通常为 0)
        writeLittleEndian16(header, 0);

        // 采样率
        writeLittleEndian32(header, sampleRate);

        // Output gain (输出增益，0 = 无增益)
        writeLittleEndian16(header, 0);

        // Channel mapping family (0 = 单声道/立体声直接映射)
        header.write(CHANNEL_MAPPING_FAMILY);

        return createOggPage(header.toByteArray(), 0, 0, true, false);
    }

    /**
     * 创建 Comment Header 页面
     */
    private byte[] createCommentHeaderPage() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();

        // OpusTags 魔数
        header.write(OPUS_TAGS_MAGIC.getBytes());

        // Vendor string length + vendor string
        String vendor = "meow-server";
        byte[] vendorBytes = vendor.getBytes();
        writeLittleEndian32(header, vendorBytes.length);
        header.write(vendorBytes);

        // Comment count (无注释)
        writeLittleEndian32(header, 0);

        return createOggPage(header.toByteArray(), 0, 0, true, false);
    }

    /**
     * 添加一帧到当前页面
     */
    private void addFrameToPage(byte[] frame) throws IOException {
        // 检查是否需要开始新页面
        int segmentCount = (currentPageData.size() + frame.length + 255) / 256;
        if (segmentCount > OGG_SEGMENT_COUNT_MAX) {
            // 当前页面已满，需要创建新页面
            byte[] pageData = currentPageData.toByteArray();
            createDataPage(pageData, false);
            currentPageData.reset();
        }

        // 写入帧长度（采用 Ogg 的 lacing 方式）
        int remaining = frame.length;
        int offset = 0;
        while (remaining > 0) {
            int segmentSize = Math.min(remaining, 255);
            currentPageData.write(segmentSize);
            currentPageData.write(frame, offset, segmentSize);
            remaining -= segmentSize;
            offset += segmentSize;
        }

        // 每 255 字节需要一个 0 长度的 segment（用于分段）
        if (frame.length > 255) {
            int segmentsNeeded = (frame.length + 254) / 255;
            int lacingBytes = segmentsNeeded - 1;
            // 已在循环中处理
        }

        sampleCount += frameSize;
    }

    /**
     * 创建数据页面
     */
    private byte[] createDataPage(byte[] data, boolean isLast) throws IOException {
        byte[] page = createOggPage(data, sampleCount, pageSequence, false, isLast);
        pageSequence++;
        return page;
    }

    /**
     * 创建 Ogg 页面
     *
     * @param data           页面数据（包含 lacing 值和实际数据）
     * @param granulePos     Granule position（样本位置）
     * @param sequence       页面序列号
     * @param isHeader       是否是头部页面
     * @param isLast         是否是最后一页
     */
    private byte[] createOggPage(byte[] data, long granulePos, long sequence, boolean isHeader, boolean isLast) throws IOException {
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();

        // 计算 segment count（lacing 值的数量）
        int segmentCount = 0;
        int dataOffset = 0;
        int totalLacedSize = 0;

        // 对于头部页面，整个 data 都是实际数据
        if (isHeader) {
            segmentCount = (data.length + 255) / 256;
        } else {
            // 对于数据页面，data 已经包含 lacing 值
            // 需要解析 lacing 值来确定实际 segment count
            int pos = 0;
            while (pos < data.length) {
                int laceValue = data[pos] & 0xFF;
                segmentCount++;
                pos++;
                if (laceValue < 255) {
                    // 这是一个完整的 segment
                } else {
                    // 继续读取，直到遇到非 255 的值
                    int continuedSize = 0;
                    while (pos < data.length && (data[pos] & 0xFF) == 255) {
                        continuedSize += 255;
                        segmentCount++;
                        pos++;
                    }
                    if (pos < data.length) {
                        int lastSegment = data[pos] & 0xFF;
                        continuedSize += lastSegment;
                        segmentCount++;
                        pos++;
                    }
                }
            }
        }

        // 开始构建 Ogg 页面
        // OggS 捕获模式
        page.write(OGG_MAGIC.getBytes());

        // 版本
        page.write(0);

        // 头部类型标志
        int headerType = 0;
        if (isHeader && sequence == 0) headerType |= 0x02; // 开始 of stream
        if (isLast) headerType |= 0x04; // 结束 of stream
        page.write(headerType);

        // Granule position
        writeLittleEndian64(page, granulePos);

        // 比特流序列号（固定为 0）
        writeLittleEndian32(page, 0);

        // 页面序列号
        writeLittleEndian32(page, sequence);

        // CRC32 (先写 0，后面计算)
        writeLittleEndian32(page, 0);

        // Segment count
        page.write(segmentCount);

        // Segment table 和数据
        if (isHeader) {
            // 头部页面：简单的分页
            int offset = 0;
            for (int i = 0; i < segmentCount - 1; i++) {
                page.write(255);
                page.write(data, offset, 255);
                offset += 255;
            }
            int lastSegment = data.length - offset;
            page.write(lastSegment);
            page.write(data, offset, lastSegment);
        } else {
            // 数据页面：data 已经包含 lacing 值，直接写入
            page.write(data);
        }

        // 计算 CRC
        byte[] pageBytes = page.toByteArray();
        crc.reset();
        crc.update(pageBytes);
        int crcValue = (int) crc.getValue();

        // 写入 CRC
        ByteBuffer.wrap(pageBytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crcValue);

        return pageBytes;
    }

    private static void writeLittleEndian16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLittleEndian32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }

    private static void writeLittleEndian64(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 32) & 0xFF));
        out.write((int) ((value >> 40) & 0xFF));
        out.write((int) ((value >> 48) & 0xFF));
        out.write((int) ((value >> 56) & 0xFF));
    }
}
