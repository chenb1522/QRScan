import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

/** 用 ZXing core 生成测试用二维码图片（纯 JDK，不依赖 Android）。 */
public class GenQr {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        new File(outDir).mkdirs();

        // 1. 中文文本（UTF-8，无 ECI，正是最容易乱码的场景）
        write(outDir + "/qr_text_cn.png",
                "二维码工具：中文内容测试 ✓ 示例文本", 720, null);
        // 2. URL
        write(outDir + "/qr_url.png", "https://github.com/zxing/zxing/wiki", 640, null);
        // 3. Wi-Fi
        write(outDir + "/qr_wifi.png",
                "WIFI:T:WPA;S:Cafe_Guest;P:Coffee2026!;H:false;;", 640, null);
        // 4. 名片
        write(outDir + "/qr_vcard.png",
                "BEGIN:VCARD\nVERSION:3.0\nN:示例;用户\nFN:示例用户\nORG:示例公司\n"
                        + "TITLE:示例职位\nTEL:10000000000\nEMAIL:user@example.com\nEND:VCARD", 720, null);
        // 5. 长文本 + 换行
        write(outDir + "/qr_long.png",
                "订单号：DEMO202611030001\n商品：二维码解析工具试用\n"
                        + "金额：¥ 128.00\n状态：已支付\n备注：这是一段较长的多行文本，用来验证滚动与换行。",
                800, null);
        // 6. 生成时强制指定 GBK 字节（模拟部分国产生成器的做法）
        writeBytes(outDir + "/qr_gbk.png",
                "GBK编码测试：中文内容需要正确还原".getBytes("GBK"), 640);
    }

    private static void write(String path, String text, int size, Map<EncodeHintType, ?> hints)
            throws Exception {
        writeBytes(path, text.getBytes(StandardCharsets.UTF_8), size);
    }

    private static void writeBytes(String path, byte[] payload, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "ISO-8859-1"); // 不写 ECI，字节原样落进去
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(
                new String(payload, StandardCharsets.ISO_8859_1), BarcodeFormat.QR_CODE,
                size, size, hints);
        BufferedImage img = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                img.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        File f = new File(path);
        ImageIO.write(img, "png", f);
        System.out.println("wrote " + path + " " + f.length() + " bytes  " + matrix.getWidth() + "px");
    }
}
