package com.qc.inspect.auth;

/**
 * 登录态载体，放在 SecurityContext 的 principal 里。
 */
public record LoginUser(Long id, String username, String realName, String role) {

    /** 展示名：有真实姓名用真实姓名，否则用账号 */
    public String displayName() {
        return realName == null || realName.isBlank() ? username : realName;
    }
}
