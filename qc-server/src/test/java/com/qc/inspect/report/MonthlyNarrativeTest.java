package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月报结论文本。
 *
 * <p>重点不是措辞好不好听，而是<b>不能说假话</b>：
 * 没有数据的月份不能输出「合格率 0%」，没有上月的月份不能输出「上升 100 个百分点」。
 * 这类错误在页面上和真实结论长得一模一样，只有单测能拦住。
 */
@DisplayName("月报结论 MonthlyNarrative")
class MonthlyNarrativeTest {

    private static MonthlyReport of(long orders, long passOrders, long total, long pass, long fail) {
        return new MonthlyReport(orders, passOrders, total, pass, fail, 0);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("空月只说「没有检验」，不许出现 0%")
    void emptyMonthDoesNotFakeZero() {
        String text = join(MonthlyNarrative.build(MonthlyReport.EMPTY, null, "2026 年 9 月",
                "", 0, 0, 0, 0, 0, 0));

        assertThat(text).contains("没有任何已提交的检验单").contains("没有检验");
        assertThat(text).as("空月最忌讳把「没做」说成「做得差」")
                .doesNotContain("0.0%").doesNotContain("100%");
    }

    @Test
    @DisplayName("有上月数据时给百分点，并带上上月数值做对照")
    void comparesWithPreviousMonth() {
        MonthlyReport cur = of(10, 9, 500, 487, 13);
        MonthlyReport prev = of(8, 7, 400, 384, 16);

        String text = join(MonthlyNarrative.build(cur, prev, "2026 年 10 月",
                "毛刺、尺寸超差、镀层脱落", 2, 1, 1, 3, 0, 0));

        assertThat(text).contains("10 张检验单").contains("受检 500 件");
        assertThat(text).contains("97.4%");
        // 96.0% → 97.4% = 上升 1.4 个百分点
        assertThat(text).contains("96.0%").contains("上升 1.4 个百分点");
        assertThat(text).doesNotContain("上升 1.4%");
    }

    @Test
    @DisplayName("上月无数据时不编造环比")
    void noPreviousMonthNoDelta() {
        MonthlyReport cur = of(1, 1, 20, 20, 0);
        String text = join(MonthlyNarrative.build(cur, MonthlyReport.EMPTY, "2026 年 10 月",
                "", 0, 0, 0, 0, 0, 0));

        assertThat(text).contains("不计算环比");
        assertThat(text).doesNotContain("上升").doesNotContain("下降");
    }

    @Test
    @DisplayName("持平要明说持平，不能说「上升 0 个百分点」")
    void flatIsStatedAsFlat() {
        MonthlyReport cur = of(2, 2, 100, 95, 5);
        MonthlyReport prev = of(2, 2, 100, 95, 5);
        String text = join(MonthlyNarrative.build(cur, prev, "2026 年 10 月", "", 0, 0, 0, 0, 0, 0));

        assertThat(text).contains("持平");
        assertThat(text).doesNotContain("上升 0").doesNotContain("下降 0");
    }

    @Test
    @DisplayName("不良为 0 时不说「不良 0 件」，直接说全部合格")
    void noDefects() {
        MonthlyReport cur = of(1, 1, 20, 20, 0);
        String text = join(MonthlyNarrative.build(cur, null, "2026 年 10 月", "", 0, 0, 0, 0, 0, 0));

        assertThat(text).contains("没有不良记录");
        assertThat(text).doesNotContain("不良 0 件");
    }

    @Test
    @DisplayName("客诉未关闭要点出它会拦出货，不能只报数字")
    void openComplaintsAreCalledOut() {
        MonthlyReport cur = of(1, 1, 20, 20, 0);
        String text = join(MonthlyNarrative.build(cur, null, "2026 年 10 月", "",
                3, 1, 1, 2, 0, 0));

        assertThat(text).contains("新开 3 起").contains("高优先级 1 起")
                .contains("关闭 1 起").contains("仍有 2 起未关闭")
                .contains("拦住对应批次出货");
        // 积压变化：3 开 1 关，一眼能看出在恶化
        assertThat(text).doesNotContain("积压已清空");
    }

    @Test
    @DisplayName("量具超期与停用都要写进结论")
    void gaugeRisksAreCalledOut() {
        MonthlyReport cur = of(1, 1, 20, 20, 0);
        String text = join(MonthlyNarrative.build(cur, null, "2026 年 10 月", "",
                0, 0, 0, 0, 2, 1));

        assertThat(text).contains("2 把已过校准有效期").contains("1 把校准不合格处于停用状态");
    }

    @Test
    @DisplayName("返工复检要报占比，让人知道返工量有多大")
    void reworkIsReported() {
        MonthlyReport cur = new MonthlyReport(10, 8, 200, 190, 10, 2);
        String text = join(MonthlyNarrative.build(cur, null, "2026 年 10 月", "", 0, 0, 0, 0, 0, 0));

        assertThat(text).contains("2 张返工复检单").contains("20.0%").contains("Ac=0");
    }
}
