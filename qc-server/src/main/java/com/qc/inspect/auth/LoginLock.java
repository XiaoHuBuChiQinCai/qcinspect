package com.qc.inspect.auth;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 登录失败锁定的口径。
 *
 * <p>做成不依赖 Spring 的纯静态类，是为了让「锁多久、几次算多、还剩几分钟」这几条
 * 能被单测直接钉住——这些数字一旦在 Dao、Service、页面三处各写一份，
 * 就会出现「后端说你还能试 2 次、页面说已锁定」这种谁也说不清的状态。
 *
 * <p>三条刻意的取舍：
 * <ul>
 *   <li><b>锁账号不锁 IP</b>：厂内几十台设备走同一个出口，锁 IP 会把整条产线一起锁掉</li>
 *   <li><b>失败不延长锁定期</b>：延长等于给了别人一个「知道账号就能让人上不了班」的开关。
 *       固定窗口把伤害限制在 15 分钟内</li>
 *   <li><b>锁定同时清零计数</b>：解锁后重新给满 5 次机会。不清零的话，
 *       期满后只要再错一次就立刻重新锁定，等于永久锁死</li>
 * </ul>
 */
public final class LoginLock {

    /** 连续错误多少次触发锁定 */
    public static final int MAX_FAILURES = 5;

    /** 锁定时长（分钟） */
    public static final int LOCK_MINUTES = 15;

    private LoginLock() {
    }

    /**
     * 还剩多少分钟解锁；未锁定返回 0。
     *
     * <p>向上取整：只剩 30 秒时说「请 1 分钟后再试」是对的，说「0 分钟」等于让人立刻再撞一次墙。
     */
    public static long minutesLeft(LocalDateTime lockedUntil, LocalDateTime now) {
        if (lockedUntil == null || now == null) {
            return 0;
        }
        long seconds = Duration.between(now, lockedUntil).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (seconds + 59) / 60;
    }

    public static boolean isLocked(LocalDateTime lockedUntil, LocalDateTime now) {
        return minutesLeft(lockedUntil, now) > 0;
    }

    /** 还锁着时的提示。必须说清「要等多久」和「忘了密码该找谁」，否则人只会反复重试 */
    public static String lockedMessage(long minutesLeft) {
        return "密码连续输错 " + MAX_FAILURES + " 次，账号已锁定，请 " + minutesLeft
                + " 分钟后再试。忘了密码请联系管理员重置";
    }

    /** 第 N 次输错正好触发锁定时的提示——不告诉人「你被锁了」，他只会以为还是密码错 */
    public static String justLockedMessage() {
        return "密码连续输错 " + MAX_FAILURES + " 次，账号已锁定 " + LOCK_MINUTES
                + " 分钟。忘了密码请联系管理员重置";
    }

    /** 这一次输错之后会不会正好锁上（before 是这次之前的连续错误次数） */
    public static boolean willLock(int failuresBefore) {
        return failuresBefore + 1 >= MAX_FAILURES;
    }
}
