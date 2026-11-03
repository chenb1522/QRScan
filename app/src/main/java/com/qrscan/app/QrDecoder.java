package com.qrscan.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 二维码/条码解码引擎。
 *
 * 设计要点：
 *  1. 实时帧 -> 直接吃 NV21 的 Y 平面（PlanarYUVLuminanceSource），零 RGB 转换开销。
 *  2. 静态图片 -> 多尺度 + 多二值化器 + 四方向旋转穷举，保证相册图片的命中率。
 *  3. 编码修复 -> 无 ECI 标记的中文二维码常被 ZXing 按 ISO-8859-1 解出乱码，
 *     这里用原始字节做严格的 UTF-8 / GBK 复解。
 */
public final class QrDecoder {

    private static final String TAG = "QRScan";

    /** 解码结果（已修正编码）。 */
    public static final class Decoded {
        public final String text;
        public final String formatName;
        public final BarcodeFormat format;
        public final long costMs;
        /** 实时帧命中时使用的顺时针旋转角，-1 表示静态图片。 */
        public final int rotation;

        Decoded(String text, BarcodeFormat format, long costMs, int rotation) {
            this.text = text;
            this.format = format;
            this.formatName = format == null ? "" : format.name();
            this.costMs = costMs;
            this.rotation = rotation;
        }
    }

    private static final EnumSet<BarcodeFormat> ALL_FORMATS = EnumSet.of(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93,
            BarcodeFormat.CODABAR,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.ITF,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E);

    private static final EnumSet<BarcodeFormat> FAST_FORMATS = EnumSet.of(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC);

    private QrDecoder() {
    }

    // ------------------------------------------------------------------
    // 提示参数
    // ------------------------------------------------------------------

    private static Map<DecodeHintType, Object> hints(boolean tryHarder, boolean qrFirst) {
        Map<DecodeHintType, Object> h = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        h.put(DecodeHintType.POSSIBLE_FORMATS, qrFirst ? FAST_FORMATS : ALL_FORMATS);
        h.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        if (tryHarder) {
            h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        }
        return h;
    }

    // ------------------------------------------------------------------
    // 实时帧解码
    // ------------------------------------------------------------------


    /**
     * 解码一帧 NV21 数据（只用 Y 平面），委托给纯 JVM 实现便于单测。
     *
     * @param yuv       预览回调数据，前 width*height 字节即 Y 平面
     * @param rotations 依次尝试的顺时针旋转角度（0/90/180/270）
     */
    public static Decoded decodeYuv(byte[] yuv, int width, int height, int[] rotations) {
        long t0 = System.currentTimeMillis();
        YuvQrDecoder.Hit hit = YuvQrDecoder.decode(yuv, width, height, rotations);
        if (hit == null) {
            return null;
        }
        return new Decoded(hit.text, hit.format, System.currentTimeMillis() - t0, hit.rotation);
    }

    // ------------------------------------------------------------------
    // 静态图片解码
    // ------------------------------------------------------------------

    /** 完整解码一张位图：多尺度 × 四方向 × 两种二值化器。 */
    public static Decoded decodeBitmap(Bitmap bitmap) {
        long t0 = System.currentTimeMillis();
        if (bitmap == null) {
            return null;
        }
        Bitmap base = normalize(bitmap);
        Map<DecodeHintType, Object> fast = hints(false, false);
        Map<DecodeHintType, Object> hard = hints(true, false);

        // 第一轮：原图 0 度，快参数
        Result r = tryBitmap(base, fast);
        if (r == null) {
            // 第二轮：四方向 × 两种二值化器
            for (int i = 0; i < 4 && r == null; i++) {
                Bitmap rotated = rotateBitmap(base, i * 90);
                r = tryBitmap(rotated, fast);
                if (rotated != base) {
                    rotated.recycle();
                }
            }
        }
        if (r == null) {
            // 第三轮：灰度拉伸 + TRY_HARDER
            Bitmap contrast = boostContrast(base);
            for (int i = 0; i < 4 && r == null; i++) {
                Bitmap rotated = rotateBitmap(contrast, i * 90);
                r = tryBitmap(rotated, hard);
                if (rotated != contrast) {
                    rotated.recycle();
                }
            }
            if (contrast != base) {
                contrast.recycle();
            }
        }
        if (base != bitmap) {
            base.recycle();
        }
        if (r == null) {
            return null;
        }
        return new Decoded(repairEncoding(r), r.getBarcodeFormat(),
                System.currentTimeMillis() - t0, -1);
    }

    /** 把过长/过短的图像拉到一个适合 ZXing 的尺寸区间。 */
    private static Bitmap normalize(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        int min = Math.min(w, h);
        float scale = 1f;
        if (max > 1800) {
            scale = 1800f / max;
        } else if (max < 480 && min > 0) {
            float up = 480f / max;
            if (up > 1f && up <= 4f) {
                scale = up;
            }
        }
        if (Math.abs(scale - 1f) < 0.01f) {
            return src;
        }
        try {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            Bitmap out = Bitmap.createBitmap(src, 0, 0, w, h, m, true);
            return out == null ? src : out;
        } catch (Throwable t) {
            return src;
        }
    }

    private static Bitmap rotateBitmap(Bitmap src, int deg) {
        if (deg % 360 == 0) {
            return src;
        }
        try {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            return out == null ? src : out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 提对比度 + 轻微锐化之前先做灰度拉伸，弱光/反光图片提升明显。 */
    private static Bitmap boostContrast(Bitmap src) {
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f);
            ColorMatrix stretch = new ColorMatrix(new float[]{
                    2.1f, 0, 0, 0, -140,
                    0, 2.1f, 0, 0, -140,
                    0, 0, 2.1f, 0, -140,
                    0, 0, 0, 1, 0});
            cm.postConcat(stretch);
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(src, 0, 0, p);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    private static Result tryBitmap(Bitmap bmp, Map<DecodeHintType, Object> hint) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 12 || h < 12) {
            return null;
        }
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        Result r = decodePixels(px, w, h, hint, true);
        if (r == null) {
            r = decodePixels(px, w, h, hint, false);
        }
        return r;
    }

    private static Result decodePixels(int[] px, int w, int h,
                                       Map<DecodeHintType, Object> hint, boolean hybrid) {
        try {
            LuminanceSource src = new RGBLuminanceSource(w, h, px);
            return new MultiFormatReader().decode(
                    new BinaryBitmap(hybrid ? new HybridBinarizer(src)
                                            : new GlobalHistogramBinarizer(src)), hint);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 编码修复
    // ------------------------------------------------------------------

    /**
     * 修复中文乱码：
     *  - QR 内容是 UTF-8 但没有 ECI 头时，ZXing 会按 ISO-8859-1 解出 "ä½ å¥½" 这类乱码；
     *  - 部分国内生成器直接写 GBK 字节。
     * 两种都用原始字节复解一次。
     */
    static String repairEncoding(Result result) {
        return EncodingFixer.repair(result);
    }


    // ------------------------------------------------------------------
    // 图片加载
    // ------------------------------------------------------------------

    /** 一次性读入字节再解码：避开 content provider 流二次打开、inMutable 等机型坑。 */
    public static Bitmap loadBitmap(Context ctx, Uri uri) {
        byte[] data = readAllBytes(ctx, uri);
        if (data == null || data.length == 0) {
            Log.w(TAG, "readAllBytes empty for " + uri);
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        Log.i(TAG, "image bytes=" + data.length + " bounds=" + w + "x" + h);

        int sample = 1;
        int max = Math.max(w, h);
        while (max / sample > 2200 && sample < 32) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bmp == null) {
            Log.w(TAG, "decodeByteArray returned null, retry plain");
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, null);
        }
        if (bmp != null) {
            Log.i(TAG, "loaded bitmap " + bmp.getWidth() + "x" + bmp.getHeight()
                    + " sample=" + sample);
        }
        return bmp;
    }

    private static byte[] readAllBytes(Context ctx, Uri uri) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "readAllBytes failed for " + uri, t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
