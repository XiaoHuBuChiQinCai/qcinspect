package com.qc.inspect.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * 图片裸地址的短时签名。
 *
 * <p>为什么需要它：{@code <img src>} 带不了 {@code Authorization} 请求头，所以
 * {@code GET /api/images/{id}/raw} 必须对未认证请求放行；而 id 是自增的，
 * 于是任何人从 1 开始遍历就能把全厂质检照片挨个拉走——现场实拍、不良件特写、
 * 客户送检样品，全在里面。照片是质量体系的客观证据，同时也是有商业价值的信息。
 *
 * <p>做法：后端生成图片地址时顺带签一个 {@code exp + HMAC}，图片接口只校验签名
 * 不再要求登录态。这样地址本身携带授权、不可枚举，而 {@code <img>} 依旧能直接显示，
 * 前端一行都不用改（地址本来就是接口返回的）。
 *
 * <p>有效期给 7 天而不是几分钟：照片会出现在报告打印、追溯页、历史单据里，
 * 一个几分钟就失效的地址会让半个月后翻单据的人看到一屏破图。7 天的窗口足够覆盖
 * 「打开页面 → 翻几页 → 关掉」，而泄漏出去的地址也不会永久有效。
 */
@Component
public class ImageToken {

    /** 签名有效期。见类注释：短了历史单据一片破图，长了失去意义 */
    private static final long TTL_SECONDS = 7L * 24 * 3600;

    /**
     * 截断长度。HMAC-SHA256 输出 32 字节，取 132 bit 已远超暴力枚举的可行性，
     * 而地址短一些便于在日志与报告 HTML 里阅读。
     */
    private static final int SIG_CHARS = 22;

    private final byte[] secret;

    public ImageToken(@Value("${qc.jwt.secret}") String secret) {
        // 与 JWT 共用密钥：本项目单机单密钥，再引入一个独立密钥只会多一处漏配的地方。
        // 用途不同不会互相削弱——签名内容里带上了 id 与过期时间，且 JWT 是完整三段式
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 生成查询串（含前导 {@code ?}），拼在 raw 地址后面 */
    public String queryOf(Long imageId) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        return "?exp=" + exp + "&sig=" + sign(imageId, exp);
    }

    /**
     * 校验。任何一项缺失都判失败——放行「没带签名」等于这次修复没做。
     *
     * <p>比较用 {@link MessageDigest#isEqual}：{@code String.equals} 遇到第一个不同字符
     * 就返回，逐字符的耗时差可以被用来一位一位试出正确签名。
     */
    public boolean verify(Long imageId, Long exp, String sig) {
        if (imageId == null || exp == null || sig == null || sig.isBlank()) {
            return false;
        }
        if (Instant.now().getEpochSecond() > exp) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(imageId, exp).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(Long imageId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal((imageId + "." + exp).getBytes(StandardCharsets.UTF_8));
            String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            return b64.substring(0, SIG_CHARS);
        } catch (Exception e) {
            // 算法名与密钥都写死在库里，走到这里说明 JRE 环境异常。
            // 抛出去而不是返回空串：返回空串会变成「校验恒失败」，图片全挂却查不出原因
            throw new IllegalStateException("图片签名失败", e);
        }
    }
}
