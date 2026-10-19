package com.qc.inspect.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 认证层的实跑验证：JWT 签发/解析/篡改/过期，以及 BCrypt 口令校验。
 * 只依赖 jar，不需要数据库和 Spring 容器。
 */
public class AuthDemo {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        String secret = "qcinspect-dev-only-secret-change-in-production-0123456789";
        JwtUtil util = new JwtUtil(secret, 60);
        LoginUser admin = new LoginUser(1L, "admin", "系统管理员", "ADMIN");

        // ---------- 签发与解析 ----------
        String token = util.issue(admin);
        check("token 是三段式", token.split("\\.").length == 3);

        LoginUser back = util.parse(token);
        check("解析成功", back != null);
        check("id 一致", back != null && back.id().equals(1L));
        check("账号一致", back != null && "admin".equals(back.username()));
        check("真实姓名一致", back != null && "系统管理员".equals(back.realName()));
        check("角色一致", back != null && "ADMIN".equals(back.role()));
        check("展示名取真实姓名", back != null && "系统管理员".equals(back.displayName()));

        // ---------- 篡改 ----------
        String tamperedPayload = tamper(token, 1);
        check("改 payload 后验签失败", util.parse(tamperedPayload) == null);
        check("乱码 token 返回 null", util.parse("not-a-token") == null);
        check("空字符串返回 null", util.parse("") == null);
        check("null 返回 null", util.parse(null) == null);

        // 用另一个密钥签的 token 必须被拒，这是验签的核心意义
        JwtUtil other = new JwtUtil("another-secret-key-with-enough-length-9876543210", 60);
        check("换密钥签发的 token 被拒", util.parse(other.issue(admin)) == null);

        // ---------- 过期 ----------
        JwtUtil shortLived = new JwtUtil(secret, -1);
        check("负数有效期签出的 token 立即过期", shortLived.parse(shortLived.issue(admin)) == null);

        // ---------- 密钥强度 ----------
        try {
            new JwtUtil("too-short", 60);
            check("短密钥应抛异常", false);
        } catch (IllegalStateException e) {
            check("短密钥被拒绝", true);
        }

        // ---------- BCrypt ----------
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("admin123");
        check("同口令校验通过", encoder.matches("admin123", hash));
        check("错口令不通过", !encoder.matches("admin1234", hash));
        check("两次哈希不同（有随机盐）", !hash.equals(encoder.encode("admin123")));
        check("哈希不是明文", !hash.contains("admin123"));
        check("BCrypt 前缀正确", hash.startsWith("$2a$"));

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 改动 payload 中间一个字符，模拟前端伪造 */
    private static String tamper(String token, int segIndex) {
        String[] parts = token.split("\\.");
        char[] chars = parts[segIndex].toCharArray();
        int mid = chars.length / 2;
        chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
        parts[segIndex] = new String(chars);
        return String.join(".", parts);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[OK]   " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }
}
