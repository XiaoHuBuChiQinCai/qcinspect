package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检验员分析的规则锁定。
 *
 * <p>这类「提示」型功能最容易失控：阈值被悄悄放宽到永远不会触发，页面看起来一切正常，
 * 实际上再也不会提醒任何人。所以每条阈值都配一个「刚好不触发」的边界用例。
 */
class InspectorInsightTest {

    /** 正常检验员：单量、件数、行数、秒数都由参数指定，其他字段给合理默认 */
    private static InspectorInsight.Raw raw(String name, long orders, long pieceTotal, long pieceFail,
                                            long rowTotal, long secTotal) {
        return new InspectorInsight.Raw(name, orders, pieceTotal, pieceTotal - pieceFail, pieceFail,
                pieceFail, 0, orders, 0, rowTotal, secTotal);
    }

    private static InspectorInsight.Row find(InspectorInsight.Result r, String name) {
        return r.rows().stream()
                .filter(x -> name.equals(x.raw().inspector()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有 " + name + "：" + r.rows()));
    }

    @Test
    @DisplayName("没有任何单据：不给 0%，summary 直说没数据")
    void emptyPeriod() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of());

        assertThat(r.rows()).isEmpty();
        assertThat(r.total().raw().orders()).isZero();
        assertThat(r.baselineNgRate()).as("分母 0 不给 0%，给 null").isNull();
        assertThat(r.total().ngRate()).isNull();
        assertThat(r.summary()).contains("没有已提交的检验单").doesNotContain("0.0%");
    }

    @Test
    @DisplayName("全组都零不良不告警：那是产品好，不是记录有问题")
    void allCleanIsNotSuspicious() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 20, 200, 0, 400, 36000),
                raw("李四", 15, 150, 0, 300, 27000)));

        assertThat(find(r, "张三").level()).isEqualTo(InspectorInsight.LEVEL_OK);
        assertThat(find(r, "张三").flags()).isEmpty();
        assertThat(find(r, "李四").level()).isEqualTo(InspectorInsight.LEVEL_OK);
        assertThat(r.summary()).contains("未发现异常信号");
    }

    @Test
    @DisplayName("同组有人判出不良，某人 10 单以上零不良 → 建议抽查")
    void zeroDefectAgainstCohort() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 12, 120, 0, 240, 36000),      // 12 单，一处不良都没有
                raw("李四", 12, 120, 10, 240, 36000)));   // 同组判出 10 件

        InspectorInsight.Row zhang = find(r, "张三");
        assertThat(zhang.level()).isEqualTo(InspectorInsight.LEVEL_SUSPECT);
        assertThat(zhang.flags()).anyMatch(f -> f.contains("12 单零不良") && f.contains("建议抽 1 单复核"));
        assertThat(find(r, "李四").flags()).as("李四没有零不良问题").noneMatch(f -> f.contains("零不良"));
    }

    @Test
    @DisplayName("零不良但单量不够（3 单）不告警：样本太小，偶然而已")
    void zeroDefectBelowMinOrders() {
        // 张三只有 3 单，既够不上零不良的 10 单门槛，也够不上组间对比的 5 单门槛
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 3, 30, 0, 60, 9000),
                raw("李四", 12, 120, 10, 240, 36000)));

        assertThat(find(r, "张三").flags()).isEmpty();
        assertThat(find(r, "张三").level()).isEqualTo(InspectorInsight.LEVEL_OK);
    }

    @Test
    @DisplayName("40 行 60 秒提交 → 每行 1.5 秒，判为录入过快")
    void rushedEntry() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 5, 50, 3, 40, 60),
                raw("李四", 5, 50, 3, 40, 3600)));

        InspectorInsight.Row zhang = find(r, "张三");
        assertThat(zhang.level()).isEqualTo(InspectorInsight.LEVEL_SUSPECT);
        assertThat(zhang.secPerRow()).isEqualTo(1.5);
        assertThat(zhang.flags()).anyMatch(f -> f.contains("平均每行 1.5 秒") && f.contains("40 行"));
    }

    @Test
    @DisplayName("行数太少时不按秒数告警：10 行 5 秒说明不了什么")
    void rushedNeedsEnoughRows() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 1, 2, 0, 10, 5),
                raw("李四", 1, 2, 0, 10, 600)));

        assertThat(find(r, "张三").flags()).noneMatch(f -> f.contains("每行"));
        assertThat(find(r, "张三").level()).isEqualTo(InspectorInsight.LEVEL_OK);
    }

    @Test
    @DisplayName("不良率不足同组三分之一 → 提示偏低，但不算记录可信度问题")
    void lowNgRate() {
        // 基线 = 20 / 200 = 10%；张三 0% 且只有 5 单（不触发零不良），低于 3.4%
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 5, 100, 0, 100, 18000),
                raw("李四", 20, 100, 20, 200, 72000)));

        InspectorInsight.Row zhang = find(r, "张三");
        assertThat(zhang.level()).isEqualTo(InspectorInsight.LEVEL_WATCH);
        assertThat(zhang.flags()).anyMatch(f -> f.contains("不足同组"));
    }

    @Test
    @DisplayName("不良率超同组两倍且不良件够多 → 提示偏高，措辞不预设原因")
    void highNgRate() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 5, 100, 40, 100, 18000),
                raw("李四", 20, 900, 10, 900, 900000)));

        InspectorInsight.Row zhang = find(r, "张三");
        assertThat(zhang.level()).isEqualTo(InspectorInsight.LEVEL_WATCH);
        assertThat(zhang.flags()).anyMatch(f -> f.contains("两倍以上")
                && f.contains("也可能判得过细"));
    }

    @Test
    @DisplayName("只有一个人时不做组间对比：拿自己比自己永远不偏")
    void singleInspectorSkipsComparison() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 30, 300, 5, 300, 54000)));

        assertThat(find(r, "张三").flags()).isEmpty();
        assertThat(find(r, "张三").level()).isEqualTo(InspectorInsight.LEVEL_OK);
    }

    @Test
    @DisplayName("同时命中零不良与录入过快时按更严重的 SUSPECT 报")
    void suspectWinsOverWatch() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 12, 120, 0, 60, 60),           // 零不良 + 每行 1 秒
                raw("李四", 12, 120, 10, 240, 36000)));

        InspectorInsight.Row zhang = find(r, "张三");
        assertThat(zhang.level()).isEqualTo(InspectorInsight.LEVEL_SUSPECT);
        assertThat(zhang.flags()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("合计行等于各行之和，比率按合计重算而不是各行平均")
    void totalRowSumsRaw() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 5, 100, 10, 100, 18000),
                raw("李四", 3, 200, 10, 200, 36000)));

        InspectorInsight.Raw t = r.total().raw();
        assertThat(t.orders()).isEqualTo(8);
        assertThat(t.pieceTotal()).isEqualTo(300);
        assertThat(t.pieceFail()).isEqualTo(20);
        // 20/300 = 6.7%，不是 (10% + 5%) / 2 = 7.5% —— 各行平均会被小单带偏
        assertThat(r.baselineNgRate()).isEqualTo(0.0667);
    }

    @Test
    @DisplayName("分母为 0 的比率一律 null：没检和全合格不是一回事")
    void zeroDenominatorIsNull() {
        InspectorInsight.Row row = InspectorInsight.analyze(List.of(
                raw("张三", 1, 0, 0, 0, 0))).rows().get(0);

        assertThat(row.ngRate()).isNull();
        assertThat(row.secPerRow()).isNull();
        // 每单时长不是零分母：开单与提交同一秒确实就是 0 分钟，这个 0 是真的
        assertThat(row.minutesPerOrder()).isZero();
    }

    @Test
    @DisplayName("summary 带人数、单量、不良率；有可疑时说明是复核记录不是查人")
    void summaryMentionsReviewNotBlame() {
        InspectorInsight.Result r = InspectorInsight.analyze(List.of(
                raw("张三", 12, 120, 0, 60, 60),
                raw("李四", 12, 120, 10, 240, 36000)));

        assertThat(r.summary())
                .contains("2 名检验员")
                .contains("24 张单")
                .contains("1 人的记录存在可信度提示")
                .contains("不是查人");
    }
}
