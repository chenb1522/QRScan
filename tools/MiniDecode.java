import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 桌面侧校验：确认生成的二维码图片本身可被 ZXing 正确解出。 */
public class MiniDecode {
    public static void main(String[] args) throws Exception {
        for (String f : args) {
            BufferedImage img = ImageIO.read(new File(f));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            LuminanceSource src = new RGBLuminanceSource(w, h, px);
            try {
                Result r = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(src)));
                String text = r.getText();
                String utf8 = new String(r.getRawBytes(), "UTF-8");
                System.out.println(new File(f).getName() + "  [" + r.getBarcodeFormat() + "]");
                System.out.println("    raw-as-utf8 : " + utf8);
                System.out.println("    zxing-text  : " + text);
                System.out.println("    same        : " + text.equals(utf8));
            } catch (Exception e) {
                System.out.println(new File(f).getName() + "  FAIL: " + e);
            }
        }
    }
}
