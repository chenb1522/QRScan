package com.qrscan.app;

import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 二维码文本编码修复（纯 JVM 逻辑，不依赖 Android，便于桌面单测）。
 *
 * 背景：大量中文二维码写入 UTF-8 字节却不带 ECI 标记，ZXing 会按 ISO-8859-1
 * 解出 "èé" 这类乱码；另有一批国产生成器直接写 GBK 字节。
 *
 * 要点：必须用 ResultMetadataType.BYTE_SEGMENTS 里的字节段，
 * 不能用 Result.getRawBytes()——后者含 mode 指示符、字符计数与填充，会污染结果。
 */
final class EncodingFixer {

    private EncodingFixer() {
    }

    static String repair(Result result) {
        if (result == null) {
            return "";
        }
        String text = result.getText();
        if (text == null) {
            text = "";
        }
        byte[] payload = payloadOf(result);
        if (payload == null || payload.length == 0 || !hasHighBytes(payload)) {
            return text;
        }

        // 1) 严格 UTF-8 且确实含非 Latin-1 字符 —— 中文/日文/emoji 的常见情形
        if (isStrictUtf8(payload)) {
            String utf8 = new String(payload, StandardCharsets.UTF_8);
            if (!utf8.equals(text) && hasCharAboveLatin1(utf8)) {
                return utf8;
            }
        }

        // 2) 看起来是 Latin-1 乱码时，再试 GBK / UTF-8
        if (looksLikeLatin1Garbage(text)) {
            String gbk = decodeStrict(payload, "GBK");
            if (gbk != null && !gbk.equals(text) && hasCharAboveLatin1(gbk)) {
                return gbk;
            }
            String utf8 = decodeStrict(payload, "UTF-8");
            if (utf8 != null && !utf8.equals(text) && hasCharAboveLatin1(utf8)) {
                return utf8;
            }
        }
        return text;
    }

    /** 取真实负载字节：优先 BYTE_SEGMENTS，退化到 rawBytes。 */
    static byte[] payloadOf(Result result) {
        try {
            Map<ResultMetadataType, Object> meta = result.getResultMetadata();
            if (meta != null) {
                Object segs = meta.get(ResultMetadataType.BYTE_SEGMENTS);
                if (segs instanceof Iterable) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    for (Object o : (Iterable<?>) segs) {
                        if (o instanceof byte[]) {
                            out.write((byte[]) o);
                        }
                    }
                    if (out.size() > 0) {
                        return out.toByteArray();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result.getRawBytes();
    }

    static boolean hasHighBytes(byte[] b) {
        if (b == null) {
            return false;
        }
        for (int i = 0; i < b.length; i++) {
            if ((b[i] & 0xFF) >= 0x80) {
                return true;
            }
        }
        return false;
    }

    static boolean isStrictUtf8(byte[] b) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static String decodeStrict(byte[] b, String charset) {
        try {
            CharsetDecoder d = Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return d.decode(ByteBuffer.wrap(b)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean hasCharAboveLatin1(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) {
                return true;
            }
        }
        return false;
    }

    /** 是否主要是 0x80-0xFF 区间的"拉丁扩展"乱码字符。 */
    static boolean looksLikeLatin1Garbage(String s) {
        if (s == null) {
            return false;
        }
        int high = 0;
        int visible = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                continue;
            }
            visible++;
            if (c >= 0x80 && c <= 0xFF) {
                high++;
            }
        }
        return visible > 0 && high * 100 / visible >= 45;
    }
}
