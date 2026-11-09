package com.qc.inspect.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录锁定的口径。
 *
 * <p>这些数字必须被钉住：一旦「5 次」「15 分钟」在 Service、Mapper、页面各写一份，
 * 迟早出现「后端说还能试两次、页面说已经锁了」这种谁也解释不清的状态。
 */
@DisplayName("登录失败锁定：次数、时长、剩余时间")
class LoginLockTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 10, 0, 0);

    @Test
    @DisplayName("从来没锁过：不算锁定")
    void neverLocked() {
        assertThat(LoginLock.minutesLeft(null, NOW)).isZero();
        assertThat(LoginLock.isLocked(null, NOW)).isFalse();
    }

    @Test
    @DisplayName("还剩 14 分 30 秒时要说 15 分钟：向上取整，不能让人对着「0 分钟」继续撞墙")
    void roundsUp() {
        assertThat(LoginLock.minutesLeft(NOW.plusSeconds(14 * 60 + 30), NOW)).isEqualTo(15);
        assertThat(LoginLock.minutesLeft(NOW.plusSeconds(60), NOW)).isEqualTo(1);
        // 只剩 1 秒也得说 1 分钟，说 0 等于告诉他「现在就能试」
        assertThat(LoginLock.minutesLeft(NOW.plusSeconds(1), NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("刚好到点就算解锁：到期是开区间，不许多关一秒")
    void expiresExactly() {
        assertThat(LoginLock.isLocked(NOW, NOW)).isFalse();
        assertThat(LoginLock.minutesLeft(NOW, NOW)).isZero();
        assertThat(LoginLock.isLocked(NOW.minusSeconds(1), NOW)).isFalse();
    }

    @Test
    @DisplayName("服务器时钟往回调过：仍按截止时间算，不会出现负数分钟")
    void handlesClockSkew() {
        assertThat(LoginLock.minutesLeft(NOW.plusMinutes(15), NOW.minusMinutes(5))).isEqualTo(20);
        assertThat(LoginLock.minutesLeft(NOW.minusMinutes(15), NOW.plusMinutes(5))).isZero();
    }

    @Test
    @DisplayName("第 5 次才锁：前 4 次只说密码错，第 5 次必须说清已被锁")
    void locksOnFifth() {
        assertThat(LoginLock.willLock(0)).isFalse();
        assertThat(LoginLock.willLock(3)).isFalse();
        assertThat(LoginLock.willLock(4)).isTrue();
        // 计数被谁多加了也不至于第 6 次才算锁（真实场景是并发多算了一次）
        assertThat(LoginLock.willLock(9)).isTrue();
    }

    @Test
    @DisplayName("提示里必须有「等多久」和「找谁」：只说「已锁定」，人只会反复重试")
    void messagesAreActionable() {
        String locked = LoginLock.lockedMessage(7);
        assertThat(locked).contains("7 分钟").contains("管理员");

        String justLocked = LoginLock.justLockedMessage();
        assertThat(justLocked).contains("5 次").contains("15 分钟").contains("管理员");
        // 锁定的提示不能说成「账号或密码错误」，否则人以为还是自己记错了
        assertThat(justLocked).doesNotContain("账号或密码错误");
    }

    @Test
    @DisplayName("口径本身钉死：5 次、15 分钟。改这两个数必须同时改这里")
    void policyIsPinned() {
        assertThat(LoginLock.MAX_FAILURES).isEqualTo(5);
        assertThat(LoginLock.LOCK_MINUTES).isEqualTo(15);
    }
}
