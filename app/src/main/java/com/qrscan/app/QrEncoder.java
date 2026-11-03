package com.qrscan.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码生成器。
 *
 * 关键点：
 *  1. 显式声明 CHARACTER_SET=UTF-8，中文会写入 ECI 标记，主流扫码器都能正确解析；
 *  2. 先按 1px/模块编码，再按整数倍放大绘制 —— 避免 ZXing 自动缩放造成的模块宽度不均与锯齿；
 *  3. 保留 2 个模块的静区（margin），这是扫码器定位所必需的；
 *  4. 提供方块/圆点两种点阵风格，中心不变形（不做 logo 挖空，保证识别率）。
 */
public final class QrEncoder {

    private QrEncoder() {
    }

    /**
     * @param text   要编码的内容
     * @param target 期望的输出边长（像素）
     * @param ec     纠错等级
     * @param round  圆角模块风格
     * @return 正方形位图，失败返回 null
     */
    public static Bitmap encode(String text, int target, ErrorCorrectionLevel ec, boolean round) {
        if (text == null || text.length() == 0) {
            return null;
        }
        int size = Math.max(240, target);
        BitMatrix base;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(
                    EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ec);
            base = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints);
        } catch (Throwable t) {
            return null;
        }
        int modules = base.getWidth();
        if (modules <= 0) {
            return null;
        }
        int scale = Math.max(1, size / modules);
        int drawn = modules * scale;
        int canvasSize = Math.max(drawn, size);

        Bitmap bmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.FILL);

        int offset = (canvasSize - drawn) / 2;
        if (round) {
            // 圆角风格的关键：只圆"整体外轮廓"的角。
            // 每个模块单独圆角会在相邻处留下白色缝隙，破坏定位图形的连续黑块，
            // 所以这里按四邻是否连通来决定哪个角需要倒圆。
            float rr = scale * 0.42f;
            Path path = new Path();
            for (int y = 0; y < modules; y++) {
                for (int x = 0; x < modules; x++) {
                    if (!base.get(x, y)) {
                        continue;
                    }
                    float x0 = offset + x * scale;
                    float y0 = offset + y * scale;
                    float x1 = x0 + scale;
                    float y1 = y0 + scale;
                    boolean up = on(base, x, y - 1, modules);
                    boolean down = on(base, x, y + 1, modules);
                    boolean left = on(base, x - 1, y, modules);
                    boolean right = on(base, x + 1, y, modules);
                    float tl = (up || left) ? 0f : rr;
                    float tr = (up || right) ? 0f : rr;
                    float br = (down || right) ? 0f : rr;
                    float bl = (down || left) ? 0f : rr;

                    path.reset();
                    path.moveTo(x0 + tl, y0);
                    path.lineTo(x1 - tr, y0);
                    if (tr > 0) {
                        path.quadTo(x1, y0, x1, y0 + tr);
                    }
                    path.lineTo(x1, y1 - br);
                    if (br > 0) {
                        path.quadTo(x1, y1, x1 - br, y1);
                    }
                    path.lineTo(x0 + bl, y1);
                    if (bl > 0) {
                        path.quadTo(x0, y1, x0, y1 - bl);
                    }
                    path.lineTo(x0, y0 + tl);
                    if (tl > 0) {
                        path.quadTo(x0, y0, x0 + tl, y0);
                    }
                    path.close();
                    c.drawPath(path, p);
                }
            }
        } else {
            for (int y = 0; y < modules; y++) {
                for (int x = 0; x < modules; x++) {
                    if (base.get(x, y)) {
                        c.drawRect(offset + x * scale, offset + y * scale,
                                offset + (x + 1) * scale, offset + (y + 1) * scale, p);
                    }
                }
            }
        }
        return bmp;
    }

    /** 判断模块是否在范围内且为黑，用于决定圆角朝向。 */
    private static boolean on(BitMatrix m, int x, int y, int size) {
        return x >= 0 && y >= 0 && x < size && y < size && m.get(x, y);
    }

    /** 预估编码后的模块数，用于在界面上提示内容容量压力。 */
    public static int modulesOf(String text, ErrorCorrectionLevel ec) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(
                    EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ec);
            return new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
                    .getWidth();
        } catch (Throwable t) {
            return 0;
        }
    }
}
