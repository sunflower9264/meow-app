package com.miaomiao.assistant.websocket.protocol;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket 二进制音频帧协议
 *
 * <p>帧结构：
 * <pre>
 * [0]   magic(0x4D)
 * [1]   messageType (1=client audio, 2=server tts)
 * [2]   flags (bit0=final)
 * [3]   formatLength (0-255)
 * [4..] format UTF-8 bytes
 * [..]  payload bytes
 * </pre>
 */
@Getter
public final class BinaryAudioFrame {

    public static final byte MAGIC = 0x4D; // 'M'
    public static final byte TYPE_CLIENT_AUDIO = 0x01;
    public static final byte TYPE_SERVER_TTS = 0x02;
    public static final byte FLAG_FINAL = 0x01;

    private final byte messageType;
    private final boolean finalChunk;
    private final String format;
    private final byte[] payload;

    private BinaryAudioFrame(byte messageType, boolean finalChunk, String format, byte[] payload) {
        this.messageType = messageType;
        this.finalChunk = finalChunk;
        this.format = format == null ? "" : format;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public static BinaryAudioFrame clientAudio(String format, byte[] payload, boolean finalChunk) {
        return new BinaryAudioFrame(TYPE_CLIENT_AUDIO, finalChunk, format, payload);
    }

    public static BinaryAudioFrame serverTTS(String format, byte[] payload, boolean finalChunk) {
        return new BinaryAudioFrame(TYPE_SERVER_TTS, finalChunk, format, payload);
    }

    public static BinaryAudioFrame decode(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 4) {
            throw new IllegalArgumentException("二进制帧长度不足");
        }

        byte magic = buffer.get();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("二进制帧 magic 非法: " + (magic & 0xFF));
        }

        byte messageType = buffer.get();
        byte flags = buffer.get();
        int formatLength = Byte.toUnsignedInt(buffer.get());

        if (buffer.remaining() < formatLength) {
            throw new IllegalArgumentException("二进制帧 formatLength 非法");
        }

        byte[] formatBytes = new byte[formatLength];
        buffer.get(formatBytes);
        String format = new String(formatBytes, StandardCharsets.UTF_8);

        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        boolean finalChunk = (flags & FLAG_FINAL) != 0;
        return new BinaryAudioFrame(messageType, finalChunk, format, payload);
    }

    public byte[] encode() {
        byte[] formatBytes = format.getBytes(StandardCharsets.UTF_8);
        if (formatBytes.length > 255) {
            throw new IllegalArgumentException("format 长度超过 255 字节");
        }

        ByteBuffer buffer = ByteBuffer.allocate(4 + formatBytes.length + payload.length);
        buffer.put(MAGIC);
        buffer.put(messageType);
        buffer.put(finalChunk ? FLAG_FINAL : 0);
        buffer.put((byte) formatBytes.length);
        buffer.put(formatBytes);
        buffer.put(payload);
        return buffer.array();
    }
}
