package com.qc.inspect.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.qc.inspect.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拍照识别条码。
 *
 * <p>为什么是后端解而不是前端解：{@code uni.scanCode} 在 H5 端根本不支持
 * （官方平台差异表里 H5 那栏就是 ×，不是「要 HTTPS」的问题），浏览器原生
 * {@code BarcodeDetector} 又只有 Chromium 系有、iOS Safari 没有，而厂里发的
 * 手机什么都有。后端用 ZXing 解，一维二维都能认，跟浏览器和协议无关——
 * http 下、微信内置浏览器、老安卓机都能用。
 *
 * <p>代价是多一次上传。所以前端先把照片压到长边 1600 再传，正常一两百 KB。
 */
@Service
public class ScanCodeService {

    /**
     * 手机原图一般 3~6MB，超过这个数基本是误传了别的文件。
     *
     * <p>故意压到 9MB、比 {@code spring.servlet.multipart.max-file-size}（10MB）低一档：
     * 撞到容器上限时抛的是 Spring 的异常，提示不如这里的具体；
     * 留在容器之内，才能由我们自己说清「是图太大」而不是「上传失败」。
     */
    private static final long MAX_BYTES = 9L * 1024 * 1024;

    /** 再大就不是「手机拍的标签」了，解出来也是 OOM */
    private static final int MAX_SOURCE_SIDE = 6000;

    /**
     * 依次尝试的长边尺寸，**从大到小**。
     *
     * <p>先 2400：手机原图动辄 4000px，按原尺寸解一次要几百毫秒还占内存，而缩到 2400
     * 对标签上的码几乎无损（细密的一维码真正怕的是缩到 1000 以下）。
     * 再往下缩，是因为「整张桌面都拍进去」的照片里码本来就小，缩小反而提高它的占比、少些噪声。
     *
     * <p>原尺寸兜底排在最后：只有所有缩小版都失败，才值得付那份时间。
     */
    private static final int[] TRY_SIDES = {2400, 1600, 1000};

    /**
     * 逐个角度试。一维码是**有方向**的，横着拍的 Code128 竖过来放，
     * ZXing 认不出——现场照片十张里有三张是竖的，这一圈不能省。
     */
    private static final int[] TRY_ROTATIONS = {0, 90, 270, 180};

    /** 现场标签一维二维都有，全给；只开 QR 会漏掉贴条形码的批次标签 */
    private static final List<BarcodeFormat> FORMATS = List.of(
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.CODABAR, BarcodeFormat.ITF,
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417,
            BarcodeFormat.AZTEC);

    /**
     * @param rotate 试到第几个角度才认出来的。90/270 说明照片是竖着拍的，
     *               前端可以据此提示「下次横着拍更快」
     */
    public record ScanResult(String text, String format, String formatText, int rotate) {
    }

    public ScanResult decode(MultipartFile file) {
        BufferedImage src = read(file);
        int longSide = Math.max(src.getWidth(), src.getHeight());

        Set<Integer> sides = new LinkedHashSet<>();
        for (int s : TRY_SIDES) {
            if (s < longSide) sides.add(s);
        }
        // 原尺寸放最后：它最占内存也最慢，前面的尺寸都白搭了才轮到它
        sides.add(longSide);

        for (int side : sides) {
            BufferedImage base = scale(src, side);
            for (int deg : TRY_ROTATIONS) {
                Result r = tryDecode(rotate(base, deg));
                if (r == null) continue;
                String text = clean(r.getText());
                if (text.isEmpty()) continue;
                return new ScanResult(text, r.getBarcodeFormat().name(),
                        formatText(r.getBarcodeFormat()), deg);
            }
        }
        throw BizException.of("没识别出条码。让码占满画面、镜头离近一点、避开反光再拍一张");
    }

    // ── 解码 ────────────────────────────────────────────────────────────────

    private Result tryDecode(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)));

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, FORMATS);
        // 标签常有磨损、折痕、塑料薄膜反光，不开 TRY_HARDER 会漏掉一大半
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        try {
            return new MultiFormatReader().decode(bitmap, hints);
        } catch (ReaderException e) {
            // NotFound / Checksum / Format 都算「这张没认出来」，继续试下一个角度
            return null;
        }
    }

    /**
     * 清掉换行和不可见字符。
     *
     * <p>一维码解出来常带尾部 {@code \r\n}（编码器按 ASCII 分段写的），
     * 不清掉就会拿 {@code "B001\n"} 去查批次，查不到还怪系统。
     */
    private static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            // 控制字符、零宽空格、BOM 一律丢
            if (c < 0x20 || c == 0x7F || c == '\uFEFF' || c == '\u200B' || c == '\u00A0') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String formatText(BarcodeFormat f) {
        return switch (f) {
            case QR_CODE -> "二维码";
            case CODE_128 -> "Code128";
            case CODE_39 -> "Code39";
            case CODE_93 -> "Code93";
            case CODABAR -> "Codabar";
            case ITF -> "ITF";
            case EAN_13 -> "EAN-13";
            case EAN_8 -> "EAN-8";
            case UPC_A -> "UPC-A";
            case UPC_E -> "UPC-E";
            case DATA_MATRIX -> "DataMatrix";
            case PDF_417 -> "PDF417";
            case AZTEC -> "Aztec";
            default -> f.name();
        };
    }

    // ── 图像处理 ────────────────────────────────────────────────────────────

    private BufferedImage read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw BizException.of("没收到图片，重拍一张");
        if (file.getSize() > MAX_BYTES) throw BizException.of("图片太大了，用手机直接拍，别再转存一遍");

        BufferedImage img;
        try (InputStream in = file.getInputStream()) {
            img = ImageIO.read(in);
        } catch (IOException e) {
            throw BizException.of("图片读不出来，可能是文件传坏了");
        }
        // HEIC 之类 ImageIO 不认的格式会返回 null，不能当成空指针往下走
        if (img == null) throw BizException.of("这个格式识别不了，用相机拍 JPG 再试");

        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) throw BizException.of("图片是空的");
        if (Math.max(w, h) > MAX_SOURCE_SIDE) {
            throw BizException.of("图片尺寸异常（" + w + "×" + h + "），用手机直接拍就行");
        }
        return img;
    }

    private static BufferedImage scale(BufferedImage src, int targetSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= targetSide) return src;

        double ratio = (double) targetSide / longSide;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static BufferedImage rotate(BufferedImage src, int deg) {
        if (deg == 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = deg % 180 != 0;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.translate(out.getWidth() / 2.0, out.getHeight() / 2.0);
        g.rotate(Math.toRadians(deg));
        g.drawImage(src, -w / 2, -h / 2, null);
        g.dispose();
        return out;
    }
}
