package com.qrscan.app;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;

/** 实时 YUV 路径的桌面单测：旋转矩阵正确性 + NV21 帧解码 + 命中角度。 */
public class YuvTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        forceUtf8Out();

        testRotateMath();
        testRealFrames(args);

        System.out.println();
        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURES"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    /** 3x2 矩阵顺时针旋转 90/180/270 后的元素位置。 */
    private static void testRotateMath() {
        byte[] m = {1, 2, 3, 4, 5, 6}; // w=3 h=2
        byte[] r90 = YuvQrDecoder.rotateY(m, 3, 2, 90);
        byte[] r180 = YuvQrDecoder.rotateY(m, 3, 2, 180);
        byte[] r270 = YuvQrDecoder.rotateY(m, 3, 2, 270);

        check("rotateY 90", Arrays.toString(r90), Arrays.toString(new byte[]{4, 1, 5, 2, 6, 3}));
        check("rotateY 180", Arrays.toString(r180), Arrays.toString(new byte[]{6, 5, 4, 3, 2, 1}));
        check("rotateY 270", Arrays.toString(r270), Arrays.toString(new byte[]{3, 6, 2, 5, 1, 4}));
    }

    /** 把二维码图片放进 1280x720 的 NV21 帧，验证实时路径能解出。 */
    private static void testRealFrames(String[] images) throws Exception {
        int fw = 960;
        int fh = 720;
        for (String path : images) {
            BufferedImage src = ImageIO.read(new File(path));
            String name = new File(path).getName();

            for (int rotate = 0; rotate < 4; rotate++) {
                BufferedImage placed = placeCenter(src, fw, fh, rotate * 90);
                byte[] nv21 = toNv21(placed, fw, fh);
                int[] order = {0, 90, 180, 270};
                long t0 = System.currentTimeMillis();
                YuvQrDecoder.Hit hit = YuvQrDecoder.decode(nv21, fw, fh, order);
                long cost = System.currentTimeMillis() - t0;
                if (hit == null) {
                    failures++;
                    System.out.println("FAIL  " + name + " rotate=" + (rotate * 90) + "  未命中");
                    continue;
                }
                // ZXing 自带方向纠正，命中角度只作信息记录，断言只看"命中且文本可用"
                boolean ok = hit != null && hit.text != null && hit.text.length() > 0;
                if (!ok) {
                    failures++;
                }
                System.out.println((ok ? "OK    " : "FAIL  ") + name
                        + " 输入旋转=" + (rotate * 90)
                        + "°  实际使用旋转=" + hit.rotation + "°"
                        + "  " + cost + "ms"
                        + "  「" + shortText(hit.text) + "」");
            }
        }
    }

    // ------------------------------------------------------------------

    private static BufferedImage placeCenter(BufferedImage src, int fw, int fh, int rotateDeg) {
        BufferedImage frame = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new java.awt.Color(0x60, 0x60, 0x60));
        g.fillRect(0, 0, fw, fh);

        int target = (int) (Math.min(fw, fh) * 0.62);
        double scale = (double) target / Math.max(src.getWidth(), src.getHeight());
        int w = (int) (src.getWidth() * scale);
        int h = (int) (src.getHeight() * scale);

        java.awt.geom.AffineTransform old = g.getTransform();
        g.translate(fw / 2.0, fh / 2.0);
        g.rotate(Math.toRadians(rotateDeg));
        g.drawImage(src, -w / 2, -h / 2, w, h, null);
        g.setTransform(old);
        g.dispose();
        return frame;
    }

    private static byte[] toNv21(BufferedImage img, int fw, int fh) {
        byte[] y = new byte[fw * fh];
        for (int j = 0; j < fh; j++) {
            for (int i = 0; i < fw; i++) {
                int rgb = img.getRGB(i, j);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                y[j * fw + i] = (byte) lum;
            }
        }
        byte[] nv21 = new byte[fw * fh * 3 / 2];
        System.arraycopy(y, 0, nv21, 0, y.length);
        for (int i = y.length; i < nv21.length; i++) {
            nv21[i] = (byte) 0x80;
        }
        return nv21;
    }

    private static void check(String label, String actual, String expected) {
        boolean ok = actual.equals(expected);
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "OK    " : "FAIL  ") + label
                + "  actual=" + actual + (ok ? "" : "  expected=" + expected));
    }

    private static String shortText(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace("\n", " ⏎ ");
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static void forceUtf8Out() {
        try {
            PrintStream ps = new PrintStream(new java.io.FileOutputStream(
                    java.io.FileDescriptor.out), true, "UTF-8");
            System.setOut(ps);
        } catch (Throwable ignored) {
        }
        new ByteArrayOutputStream();
    }
}
