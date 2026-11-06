package com.qc.inspect.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.qc.inspect.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拍照解码。
 *
 * <p>为什么不喂现成的图片文件：一张标签照片进仓库就是几十上百 KB 的二进制，
 * 而且没人知道它当初拍的是什么方向、什么分辨率。这里用 ZXing 自己画码出来，
 * 想考哪个分支就造哪张图——竖着拍的、码很小的、带换行的，都能精确复现。
 *
 * <p>这条链路最容易坏的地方不是「能不能解」，而是**能解的条件太窄**：
 * 只试 0 度（竖拍的全军覆没）、只试原尺寸（手机原图 4000px 一张解 3 秒）、
 * 不清理尾部换行（拿 "B001\n" 去查批次，永远查不到）。
 */
class ScanCodeTest {

    private final ScanCodeService service = new ScanCodeService();

    @Test
    @DisplayName("二维码：解出的文本与写入的一字不差")
    void decodesQrCode() throws Exception {
        var res = service.decode(png(qr("QC-B001-2026", 300)));

        assertThat(res.text()).isEqualTo("QC-B001-2026");
        assertThat(res.format()).isEqualTo("QR_CODE");
        assertThat(res.formatText()).isEqualTo("二维码");
        assertThat(res.rotate()).isZero();
    }

    @Test
    @DisplayName("一维码：Code128 也认，别只支持二维码")
    void decodesCode128() throws Exception {
        var res = service.decode(png(code128("B0012026001", 600, 200)));

        assertThat(res.text()).isEqualTo("B0012026001");
        assertThat(res.format()).isEqualTo("CODE_128");
        assertThat(res.formatText()).isEqualTo("Code128");
    }

    @Test
    @DisplayName("竖着拍的：旋转 90° 仍能解出来，不然现场一半照片白拍")
    void decodesRotated() throws Exception {
        // 逆时针转 90：还原要顺时针转 90，正好落在 TRY_ROTATIONS 的第二个
        var res = service.decode(png(rotated(code128("B0022026002", 600, 200), -90)));

        assertThat(res.text()).isEqualTo("B0022026002");
        assertThat(res.rotate()).isIn(90, 270);
    }

    @Test
    @DisplayName("大图里的小码：整张桌面拍进去也能抓出来")
    void decodesSmallCodeInLargePhoto() throws Exception {
        // 3000×3000 的画布上贴一个 240px 的码，差不多是「手机站远了拍」的效果
        BufferedImage big = new BufferedImage(3000, 3000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 3000, 3000);
        g.drawImage(qr("B0032026003", 240), 1400, 1500, null);
        g.dispose();

        assertThat(service.decode(png(big)).text()).isEqualTo("B0032026003");
    }

    @Test
    @DisplayName("尾巴上的换行要去掉：拿 \"B001\\n\" 去查批次永远查不到")
    void stripsTrailingControlChars() throws Exception {
        // 一维码编码器习惯在末尾补 \r\n，二维码这里同样能造出来
        var res = service.decode(png(qr("B0042026004\r\n", 300)));

        assertThat(res.text()).isEqualTo("B0042026004");
    }

    @Test
    @DisplayName("空图：给出能照做的提示，不是 500")
    void blankImageGivesActionableMessage() throws Exception {
        BufferedImage white = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = white.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        assertThatThrownBy(() -> service.decode(png(white)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没识别出条码");
    }

    @Test
    @DisplayName("不是图片的文件：说清楚是格式问题，别抛空指针")
    void rejectsNonImage() {
        var notImage = new MockMultipartFile("file", "a.txt", "text/plain",
                "这不是图片".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.decode(notImage))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式");
    }

    @Test
    @DisplayName("空文件与超大文件在解码前就拦掉")
    void rejectsEmptyAndOversized() {
        assertThatThrownBy(() -> service.decode(new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(BizException.class);

        byte[] huge = new byte[13 * 1024 * 1024];
        assertThatThrownBy(() -> service.decode(new MockMultipartFile("file", "big.jpg", "image/jpeg", huge)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("太大了");
    }

    // ── 造图 ────────────────────────────────────────────────────────────────

    private static BufferedImage qr(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        return render(new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints));
    }

    private static BufferedImage code128(String text, int w, int h) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // 留够静区，否则 ZXing 找不到条码的起止（真实标签上印糊了就是这个后果）
        hints.put(EncodeHintType.MARGIN, 20);
        return render(new MultiFormatWriter().encode(text, BarcodeFormat.CODE_128, w, h, hints));
    }

    private static BufferedImage render(BitMatrix m) {
        int w = m.getWidth();
        int h = m.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    private static BufferedImage rotated(BufferedImage src, int deg) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = deg % 180 != 0;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.translate(out.getWidth() / 2.0, out.getHeight() / 2.0);
        g.rotate(Math.toRadians(deg));
        g.drawImage(src, -w / 2, -h / 2, null);
        g.dispose();
        return out;
    }

    private static MockMultipartFile png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("file", "scan.png", "image/png", out.toByteArray());
    }
}
