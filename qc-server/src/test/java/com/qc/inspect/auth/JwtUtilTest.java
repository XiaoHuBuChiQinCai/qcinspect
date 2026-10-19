package com.qc.inspect.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 与口令散列测试。
 *
 * <p>验签失败必须返回 null 并拒绝放行——只要有一条路径能接受伪造 token，
 * 整个权限体系就形同虚设。所以篡改、换密钥、过期三种情况逐个钉住。
 */
@DisplayName("认证：JWT 与 BCrypt")
class JwtUtilTest {

    private static final String SECRET = "qcinspect-test-secret-0123456789-abcdefghij";

    private static JwtUtil util(long minutes) {
        return new JwtUtil(SECRET, minutes);
    }

    private static final LoginUser ADMIN = new LoginUser(1L, "admin", "系统管理员", "ADMIN");

    @Test
    @DisplayName("签发后能解析，四个字段原样返回")
    void issueAndParse() {
        JwtUtil jwt = util(480);
        String token = jwt.issue(ADMIN);

        assertThat(token.split("\\.")).hasSize(3);

        LoginUser back = jwt.parse(token);
        assertThat(back).isNotNull();
        assertThat(back.id()).isEqualTo(1L);
        assertThat(back.username()).isEqualTo("admin");
        assertThat(back.realName()).isEqualTo("系统管理员");
        assertThat(back.role()).isEqualTo("ADMIN");
        assertThat(back.displayName()).isEqualTo("系统管理员");
    }

    @Test
    @DisplayName("displayName 在没有真实姓名时回落到账号")
    void displayNameFallback() {
        JwtUtil jwt = util(480);
        LoginUser noRealName = new LoginUser(9L, "zhangsan", null, "INSPECTOR");
        assertThat(jwt.issue(noRealName)).isNotBlank();
        assertThat(noRealName.displayName()).isEqualTo("zhangsan");
    }

    @Test
    @DisplayName("篡改 payload → 验签失败返回 null")
    void tamperedPayloadRejected() {
        JwtUtil jwt = util(480);
        String token = jwt.issue(ADMIN);
        String[] parts = token.split("\\.");

        // 把角色从 ADMIN 改成别的，签名对不上
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                        .replace("ADMIN", "ROOT").getBytes());
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwt.parse(tampered)).isNull();
    }

    @Test
    @DisplayName("换一个密钥签发的 token 必须被拒（否则谁都能自签）")
    void otherSecretRejected() {
        JwtUtil mine = util(480);
        JwtUtil other = new JwtUtil("another-secret-0123456789-abcdefghijklmn", 480);
        assertThat(mine.parse(other.issue(ADMIN))).isNull();
    }

    @Test
    @DisplayName("有效期已过 → 返回 null")
    void expiredRejected() {
        JwtUtil shortLived = util(-1);
        String token = shortLived.issue(ADMIN);
        assertThat(shortLived.parse(token)).isNull();
    }

    @Test
    @DisplayName("垃圾输入不抛异常，一律返回 null")
    void garbageInput() {
        JwtUtil jwt = util(480);
        assertThat(jwt.parse("not-a-token")).isNull();
        assertThat(jwt.parse("")).isNull();
        assertThat(jwt.parse(null)).isNull();
        assertThat(jwt.parse("aaa.bbb.ccc")).isNull();
    }

    @Test
    @DisplayName("密钥不足 32 字节直接启动失败，不留弱密钥后门")
    void weakSecretRejected() {
        assertThatThrownBy(() -> new JwtUtil("short", 480))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("有效期按配置返回")
    void expireMinutes() {
        assertThat(util(120).expireMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("BCrypt：正确口令通过，错口令不通过，散列带随机盐")
    void bcryptBehaviour() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("admin123");

        assertThat(encoder.matches("admin123", hash)).isTrue();
        assertThat(encoder.matches("admin1234", hash)).isFalse();
        assertThat(hash).isNotEqualTo(encoder.encode("admin123"));
        assertThat(hash).doesNotContain("admin123");
        assertThat(hash).startsWith("$2a$");
    }
}
