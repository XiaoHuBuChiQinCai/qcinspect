package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月报指标计算。
 *
 * <p>这些纯函数看着像废话（不就是除一下），但月报里最容易出事的恰恰是它们：
 * 分母 0 时返回 0 还是 null、环比说的是「百分点」还是「百分比」，
 * 写错了报表照样生成，只是拿到会上会被人当面问住。
 */
@DisplayName("月报指标 MonthlyReport")
class MonthlyReportTest {

    private static MonthlyReport of(long orders, long passOrders, long total, long pass, long fail) {
        return new MonthlyReport(orders, passOrders, total, pass, fail, 0);
    }

    @Test
    @DisplayName("分母为 0 时合格率是 null，不是 0%")
    void noDataIsNullNotZero() {
        MonthlyReport empty = MonthlyReport.EMPTY;
        assertThat(empty.yieldRate()).as("没做检验 ≠ 全不合格").isNull();
        assertThat(empty.orderYieldRate()).isNull();
        assertThat(empty.ppm()).isNull();
        assertThat(empty.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("做了检验且全不合格才是 0%，空月不能混成同一个 0")
    void zeroYieldIsRealZero() {
        MonthlyReport allFail = of(2, 0, 100, 0, 100);
        assertThat(allFail.yieldRate()).isEqualTo(0.0);
        assertThat(allFail.isEmpty()).isFalse();
        assertThat(allFail.ppm()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("件级合格率保留 4 位，不因浮点尾差抖动")
    void yieldRateRounding() {
        // 97 / 1000 = 9.7%；用 3 件里 2 件合格 = 0.6667
        assertThat(of(1, 1, 3, 2, 1).yieldRate()).isEqualTo(0.6667);
        assertThat(of(1, 1, 8, 7, 1).yieldRate()).isEqualTo(0.875);
    }

    @Test
    @DisplayName("PPM 按百万件不良数换算，四舍五入到整数")
    void ppm() {
        assertThat(of(1, 1, 1000, 997, 3).ppm()).isEqualTo(3000L);
        // 1 / 3 = 333333.33 → 333333
        assertThat(of(1, 0, 3, 2, 1).ppm()).isEqualTo(333333L);
    }

    @Test
    @DisplayName("环比用百分点，不是百分比")
    void deltaIsPercentagePoint() {
        MonthlyReport prev = of(1, 1, 100, 96, 4);
        MonthlyReport cur = of(1, 1, 100, 97, 3);
        // 96% → 97% 是上升 1 个百分点；说成「上升 1%」会被理解成不良率改善 1%
        assertThat(cur.yieldDeltaPp(prev)).isEqualTo(1.0);
        assertThat(prev.yieldDeltaPp(cur)).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("任一期间没有数据就不算环比，避免「上升 100 个百分点」")
    void deltaNeedsBothPeriods() {
        MonthlyReport cur = of(1, 1, 100, 97, 3);
        assertThat(cur.yieldDeltaPp(null)).isNull();
        assertThat(cur.yieldDeltaPp(MonthlyReport.EMPTY)).isNull();
        assertThat(MonthlyReport.EMPTY.yieldDeltaPp(cur)).isNull();
    }

    @Test
    @DisplayName("按检验类型分组的结果能汇总回整月")
    void sumParts() {
        MonthlyReport iqc = new MonthlyReport(2, 1, 50, 45, 5, 0);
        MonthlyReport fqc = new MonthlyReport(3, 3, 30, 30, 0, 2);
        MonthlyReport all = MonthlyReport.sum(List.of(iqc, fqc));

        assertThat(all.orders()).isEqualTo(5);
        assertThat(all.passOrders()).isEqualTo(4);
        assertThat(all.pieceTotal()).isEqualTo(80);
        assertThat(all.piecePass()).isEqualTo(75);
        assertThat(all.pieceFail()).isEqualTo(5);
        assertThat(all.recheckOrders()).isEqualTo(2);
        assertThat(all.orderYieldRate()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("返工复检率以检验单为分母")
    void recheckRate() {
        assertThat(new MonthlyReport(4, 2, 80, 70, 10, 1).recheckRate()).isEqualTo(0.25);
    }
}
