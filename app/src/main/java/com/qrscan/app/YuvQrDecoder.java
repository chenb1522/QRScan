package com.qrscan.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 实时预览帧（NV21 的 Y 平面）解码器 —— 纯 JVM，无 Android 依赖，便于桌面单测。
 *
 * 相机预览帧是传感器原始方向（多数机型需顺时针转 90 度），所以这里按候选角度
 * 依次旋转 Y 平面后尝试解码，命中即返回实际生效的角度，供上层做命中角度缓存。
 */
final class YuvQrDecoder {

    static final class Hit {
        final String text;
        final BarcodeFormat format;
        final int rotation;

        Hit(String text, BarcodeFormat format, int rotation) {
            this.text = text;
            this.format = format;
            this.rotation = rotation;
        }
    }

    private static final EnumSet<BarcodeFormat> FORMATS = EnumSet.of(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC);

    private YuvQrDecoder() {
    }

    static Hit decode(byte[] yuv, int width, int height, int[] rotations) {
        if (yuv == null || width < 16 || height < 16 || yuv.length < width * height) {
            return null;
        }
        Map<DecodeHintType, Object> hint = hints();
        for (int i = 0; i < rotations.length; i++) {
            int deg = ((rotations[i] % 360) + 360) % 360;
            byte[] plane = yuv;
            int w = width;
            int h = height;
            if (deg != 0) {
                plane = rotateY(yuv, width, height, deg);
                if (deg != 180) {
                    w = height;
                    h = width;
                }
            }
            Result r = decodePlane(plane, w, h, hint);
            if (r != null) {
                return new Hit(EncodingFixer.repair(r), r.getBarcodeFormat(), deg);
            }
        }
        return null;
    }

    private static Map<DecodeHintType, Object> hints() {
        Map<DecodeHintType, Object> h = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        h.put(DecodeHintType.POSSIBLE_FORMATS, FORMATS);
        h.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return h;
    }

    private static Result decodePlane(byte[] plane, int w, int h,
                                      Map<DecodeHintType, Object> hint) {
        try {
            LuminanceSource src = new PlanarYUVLuminanceSource(plane, w, h, 0, 0, w, h, false);
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(src)), hint);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 对 Y 平面做顺时针旋转。
     * 旋转后矩阵尺寸变为 h×w（90/270）或保持 w×h（180）。
     */
    static byte[] rotateY(byte[] src, int w, int h, int deg) {
        int n = w * h;
        byte[] out = new byte[n];
        if (deg == 180) {
            for (int i = 0; i < n; i++) {
                out[i] = src[n - 1 - i];
            }
            return out;
        }
        if (deg == 90) {
            // dst(x', y') = src(x, y)，其中 x' = h-1-y，y' = x；dst 行宽 = h
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    out[x * h + (h - 1 - y)] = src[row + x];
                }
            }
            return out;
        }
        // 270：x' = y，y' = w-1-x
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                out[(w - 1 - x) * h + y] = src[row + x];
            }
        }
        return out;
    }
}
