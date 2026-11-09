package com.qc.inspect.rework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 返修报数与超期判定。
 *
 * <p>这些断言锁的是「账要对得上」：返好 + 返废 必须正好等于送修。
 * 库层有 CHECK 兜底，但库层只会甩一句约束名，界面上要的是
 * 「少了 2 件，那两件去哪了」这种能直接拿去做事的提示。
 */
class ReworkBalanceTest {

    private static LocalDate day(int d) {
        return LocalDate.of(2026, 11, d);
    }

    // ==================== 送修数量 ====================

    @Test
    @DisplayName("送修数量：0 件不是单据，是误操作")
    void zeroQtyRejected() {
        assertThat(ReworkBalance.checkQty(0).ok()).isFalse();
        assertThat(ReworkBalance.checkQty(0).message()).contains("大于 0");
        assertThat(ReworkBalance.checkQty(-3).ok()).isFalse();
    }

    @Test
    @DisplayName("送修数量：不填要说清楚，不能等到库层抛约束")
    void nullQtyRejected() {
        assertThat(ReworkBalance.checkQty(null).ok()).isFalse();
        assertThat(ReworkBalance.checkQty(null).message()).contains("送修数量");
    }

    @Test
    @DisplayName("送修数量：一张票最多 500 件")
    void tooManyRejected() {
        assertThat(ReworkBalance.checkQty(ReworkBalance.MAX_QTY).ok()).isTrue();
        ReworkBalance.Check c = ReworkBalance.checkQty(ReworkBalance.MAX_QTY + 1);
        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("拆成多张");
    }

    // ==================== 完工报数 ====================

    @Test
    @DisplayName("报数：返好 + 返废 正好等于送修才放行")
    void balancedPasses() {
        assertThat(ReworkBalance.checkFinish(5, 5, 0).ok()).isTrue();
        assertThat(ReworkBalance.checkFinish(5, 4, 1).ok()).isTrue();
        assertThat(ReworkBalance.checkFinish(5, 0, 5).ok()).isTrue();
    }

    @Test
    @DisplayName("报数：少报件数要点出还差几件——那几件多半还在返修台上")
    void shortAnswerNamesTheGap() {
        ReworkBalance.Check c = ReworkBalance.checkFinish(5, 3, 1);

        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("少了 1 件");
        // 数字要出现在提示里，否则人还得自己算一遍
        assertThat(c.message()).contains("返好 3 件", "返废 1 件", "送修的 5 件");
    }

    @Test
    @DisplayName("报数：多报件数要怀疑是不是记错了票")
    void overAnswerSuspicious() {
        ReworkBalance.Check c = ReworkBalance.checkFinish(5, 4, 2);

        assertThat(c.ok()).isFalse();
        assertThat(c.message()).contains("多了 1 件", "记错了票");
    }

    @Test
    @DisplayName("报数：返废填 0 也算交代过了，但空着不行")
    void blankScrapNotAllowed() {
        // 5 件全修好、报废 0，是正常结果，必须放行
        assertThat(ReworkBalance.checkFinish(5, 5, null).ok()).isFalse();
        assertThat(ReworkBalance.checkFinish(5, null, 0).ok()).isFalse();
        assertThat(ReworkBalance.checkFinish(5, 5, 0).ok()).isTrue();
    }

    @Test
    @DisplayName("报数：负数直接拒，不能让返废的件倒着长出来")
    void negativeRejected() {
        assertThat(ReworkBalance.checkFinish(5, 6, -1).ok()).isFalse();
        assertThat(ReworkBalance.checkFinish(5, -1, 6).ok()).isFalse();
    }

    // ==================== 超期 ====================

    @Test
    @DisplayName("超期：要求完成日早于今天才算，当天到期不算")
    void overdueBoundary() {
        // 边界开闭区间最容易错：今天到期不代表「已经超了」，
        // 否则每天一到零点，所有今天该交的票全变红字
        assertThat(ReworkBalance.overdue(day(9), "OPEN", day(9))).isFalse();
        assertThat(ReworkBalance.overdue(day(8), "OPEN", day(9))).isTrue();
        assertThat(ReworkBalance.overdue(day(10), "OPEN", day(9))).isFalse();
    }

    @Test
    @DisplayName("超期：完工和取消的票不再飘红字")
    void finishedNotOverdue() {
        assertThat(ReworkBalance.overdue(day(1), "DONE", day(9))).isFalse();
        assertThat(ReworkBalance.overdue(day(1), "CANCELLED", day(9))).isFalse();
        assertThat(ReworkBalance.overdue(day(1), "DOING", day(9))).isTrue();
    }

    @Test
    @DisplayName("超期：没定期限就不催")
    void noDueDateNoNudge() {
        assertThat(ReworkBalance.overdue(null, "OPEN", day(9))).isFalse();
        assertThat(ReworkBalance.overdue(day(1), null, day(9))).isFalse();
        assertThat(ReworkBalance.overdue(day(1), "OPEN", null)).isFalse();
    }

    @Test
    @DisplayName("超期天数：按天算，不按小时")
    void overdueDays() {
        assertThat(ReworkBalance.overdueDays(day(6), day(9))).isEqualTo(3);
        assertThat(ReworkBalance.overdueDays(day(9), day(9))).isZero();
        assertThat(ReworkBalance.overdueDays(day(10), day(9))).isZero();
        assertThat(ReworkBalance.overdueDays(null, day(9))).isZero();
    }

    // ==================== 默认期限 ====================

    @Test
    @DisplayName("默认要求完成日：三天后——短到会被发现，长到不至于当天就红")
    void defaultDueDate() {
        assertThat(ReworkBalance.defaultDueDate(day(9))).isEqualTo(day(12));
        assertThat(ReworkBalance.overdue(ReworkBalance.defaultDueDate(day(9)), "OPEN", day(9))).isFalse();
    }
}
