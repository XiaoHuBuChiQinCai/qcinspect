package com.qc.inspect.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片地址签名。
 *
 * <p>这套签名是 {@code /api/images/{id}/raw} 唯一的授权手段——那个接口必须对未认证
 * 请求开放（{@code <img>} 带不了请求头），所以一旦签名可被绕过或猜出，全厂照片
 * 就是公开的。用例按「攻击者能做哪些事」来组织，而不是按方法分支。
 */
class ImageTokenTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes-long";

    private final ImageToken token = new ImageToken(SECRET);

    /** 从 {@code ?exp=..&sig=..} 里取参数 */
    private static long expOf(String query) {
        return Long.parseLong(query.substring(query.indexOf("exp=") + 4, query.indexOf("&sig=")));
    }

    private static String sigOf(String query) {
        return query.substring(query.indexOf("&sig=") + 5);
    }

    @Test
    @DisplayName("自己签的能验过：否则线上每张图都是破图")
    void ownSignaturePasses() {
        String query = token.queryOf(42L);
        assertThat(token.verify(42L, expOf(query), sigOf(query))).isTrue();
    }

    @Test
    @DisplayName("拿 A 的签名去换 B：不能因为「签名是对的」就放行别的 id")
    void signatureIsBoundToId() {
        String query = token.queryOf(42L);
        assertThat(token.verify(43L, expOf(query), sigOf(query))).isFalse();
    }

    @Test
    @DisplayName("改过期时间：把 exp 往后延必须破坏签名，否则过期就是一纸空文")
    void cannotExtendExpiry() {
        String query = token.queryOf(42L);
        long exp = expOf(query);
        assertThat(token.verify(42L, exp + 86400, sigOf(query))).isFalse();
        assertThat(token.verify(42L, exp - 86400, sigOf(query))).isFalse();
    }

    @Test
    @DisplayName("过期就是过期：签名本身算得没错，也不能再用")
    void expiredRejected() throws Exception {
        long past = Instant.now().getEpochSecond() - 60;
        // 拿一个「对过去的 exp 正确算出」的签名：这条路径才真正考到时间检查。
        // 用 queryOf 给的签名配一个过去的 exp 是考不到的——那种组合签名本来就不对，
        // 无论有没有时间检查都会返回 false，用例就白写了
        assertThat(token.verify(42L, past, signRaw(42L, past))).isFalse();

        // 对照组：同样一段代码，exp 在未来就能过。证明上面的 false 来自时间检查
        long future = Instant.now().getEpochSecond() + 60;
        assertThat(token.verify(42L, future, signRaw(42L, future))).isTrue();
    }

    @Test
    @DisplayName("少一个参数也要拒：lenient 一点等于这个修复没做")
    void missingParamsRejected() {
        String query = token.queryOf(42L);
        assertThat(token.verify(42L, null, sigOf(query))).isFalse();
        assertThat(token.verify(42L, expOf(query), null)).isFalse();
        assertThat(token.verify(42L, expOf(query), "")).isFalse();
        assertThat(token.verify(null, expOf(query), sigOf(query))).isFalse();
    }

    @Test
    @DisplayName("换个密钥签出来的不通用：密钥变了等于全部地址作废，这是预期行为")
    void secretMatters() {
        String other = new ImageToken("another-secret-also-32-bytes-long-xxxx").queryOf(42L);
        assertThat(token.verify(42L, expOf(other), sigOf(other))).isFalse();
    }

    @Test
    @DisplayName("同一 (id, exp) 签出来的必须一样，否则幂等重签会抖")
    void deterministic() throws Exception {
        long exp = 1_900_000_000L;
        assertThat(signRaw(7L, exp)).isEqualTo(signRaw(7L, exp));
        assertThat(signRaw(7L, exp)).isNotEqualTo(signRaw(7L, exp + 1));
    }

    /** 直接调私有签名，用来构造「对任意 exp 都正确」的签名 */
    private String signRaw(Long id, long exp) throws Exception {
        var m = ImageToken.class.getDeclaredMethod("sign", Long.class, long.class);
        m.setAccessible(true);
        return (String) m.invoke(token, id, exp);
    }
}
