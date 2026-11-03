package com.qrscan.app;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/** 桌面侧端到端校验：ZXing 原始文本 vs EncodingFixer 修复后的文本。 */
public class RepairCheck {

    public static void main(String[] args) throws Exception {
        forceUtf8Out();
        int pass = 0;
        int total = 0;
        for (String f : args) {
            total++;
            BufferedImage img = ImageIO.read(new File(f));
            int w = img.getWidth();
            int h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            LuminanceSource src = new RGBLuminanceSource(w, h, px);
            String fixed;
            String raw;
            try {
                Result r = new MultiFormatReader()
                        .decode(new BinaryBitmap(new HybridBinarizer(src)));
                raw = r.getText();
                fixed = EncodingFixer.repair(r);
            } catch (Exception e) {
                System.out.println("== " + new File(f).getName() + "  DECODE FAIL " + e);
                continue;
            }
            boolean ok = looksRight(fixed);
            if (ok) {
                pass++;
            }
            System.out.println("== " + new File(f).getName() + (ok ? "  [OK]" : "  [SUSPECT]"));
            System.out.println("   zxing : " + oneLine(raw));
            System.out.println("   fixed : " + oneLine(fixed));
        }
        System.out.println();
        System.out.println("RESULT " + pass + "/" + total + " 看起来正确");
    }

    /** 修复后不应再出现 "Ã/Â/ä¸" 这类 Latin-1 乱码特征。 */
    private static boolean looksRight(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        int bad = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xC0 && c <= 0xFF) {
                bad++;
            }
        }
        return bad * 100 / s.length() < 5;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace("\r\n", "\n").replace("\n", " ⏎ ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static void forceUtf8Out() {
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        } catch (UnsupportedEncodingException ignored) {
        }
        try {
            System.setOut(new PrintStream(new java.io.FileOutputStream(
                    java.io.FileDescriptor.out), true, "UTF-8"));
        } catch (Throwable ignored) {
        }
    }
}
