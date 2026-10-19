package com.qc.inspect.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录人。接口层不再信任前端传来的 operator 参数，一律以 token 为准。
 */
public final class UserContext {

    public static final String ANONYMOUS = "anonymous";

    private UserContext() {
    }

    public static LoginUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof LoginUser u ? u : null;
    }

    /** 当前登录人的展示名，未登录返回 anonymous（供定时任务、单测等无上下文场景使用） */
    public static String username() {
        LoginUser u = current();
        return u == null ? ANONYMOUS : u.displayName();
    }

    /**
     * 操作人归属：优先取登录态，未登录时才用调用方传入的值。
     * 这样历史接口（operator 走 query 参数）不必一次性改完，接上认证后自动变安全。
     */
    public static String operator(String fallback) {
        LoginUser u = current();
        if (u != null) {
            return u.displayName();
        }
        return fallback == null || fallback.isBlank() ? ANONYMOUS : fallback;
    }

    public static boolean isAdmin() {
        LoginUser u = current();
        return u != null && "ADMIN".equals(u.role());
    }
}
