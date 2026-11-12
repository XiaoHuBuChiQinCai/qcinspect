package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商画像的判断。
 *
 * <p>这块最要紧的断言是<b>「偶发」和「系统性」必须分开</b>——
 * 两者都可能写着 95% 的合格率，但一个要查那一批、一个要改工艺，
 * 说反了就是让采购去谈一件不该谈的事（或者干脆换掉一家其实很好的厂）。
 *
 * <p>第二要紧的是<b>样本不够时闭嘴</b>。三批里坏两批就说「每批都有问题」，
 * 这话在会上一句「那才三批」就被顶回来，之后再说什么都没人信了。
 */
@DisplayName("供应商画像 · 问题性质与趋势")
class SupplierInsightTest {

    private static SupplierInsight.Profile p(long batches, long lots, long total, long pass,
                                             long perfect, long bad, Double worst) {
        return new SupplierInsight.Profile(batches, lots, total, pass, perfect, bad, worst);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------ 分档

    @Test
    @DisplayName("一张单都没有：说清楚「没挂档的批次根本不会进这里」")
    void 无数据() {
        SupplierInsight.Profile empty = p(0, 0, 0, 0, 0, 0, null);
        assertThat(SupplierInsight.kind(empty)).isEqualTo(SupplierInsight.Kind.NO_DATA);
        String text = join(SupplierInsight.build(empty, null, null, null));
        assertThat(text).contains("没有这家供应商的检验记录");
        // 这句是给「查了没数据」的人看的：数据为空的常见原因是批次没挂 supplier_id，
        // 而不是这家厂真的没问题
        assertThat(text).contains("没挂档");
    }

    @Test
    @DisplayName("样本不足三张：不下结论，也不能出现「系统性」")
    void 样本不足() {
        SupplierInsight.Profile few = p(2, 2, 40, 30, 0, 2, 0.60);
        assertThat(SupplierInsight.kind(few)).isEqualTo(SupplierInsight.Kind.TOO_FEW);
        String text = join(SupplierInsight.build(few, null, null, null));
        assertThat(text).contains("还不足以下判断");
        assertThat(text).contains("很可能只是运气");
        assertThat(text).doesNotContain("系统性").doesNotContain("每批都");
    }

    @Test
    @DisplayName("零不良：提醒「没检出来」不是「确实没有」")
    void 零不良() {
        SupplierInsight.Profile clean = p(5, 5, 80, 80, 5, 0, 1.0);
        assertThat(SupplierInsight.kind(clean)).isEqualTo(SupplierInsight.Kind.CLEAN);
        String text = join(SupplierInsight.build(clean, null, null, null));
        assertThat(text).contains("没有出现任何不良");
        // 抽检 5 件就宣称「这家厂没问题」是最容易犯的错
        assertThat(text).contains("没检出来").contains("抽检比例低");
    }

    @Test
    @DisplayName("12 批里 1 批出问题 → 偶发：要查那一批，不是换供应商")
    void 偶发() {
        SupplierInsight.Profile sporadic = p(12, 12, 240, 228, 11, 1, 0.40);
        assertThat(SupplierInsight.kind(sporadic)).isEqualTo(SupplierInsight.Kind.SPORADIC);
        String text = join(SupplierInsight.build(sporadic, null, null, null));
        assertThat(text).contains("这是偶发");
        assertThat(text).contains("换供应商是最贵的一种错");
        assertThat(text).doesNotContain("每批都");
    }

    @Test
    @DisplayName("12 批每批都有不良 → 系统性：不是运气，是能力，谈的是工艺")
    void 系统性() {
        SupplierInsight.Profile systematic = p(12, 12, 240, 228, 0, 12, 0.90);
        assertThat(SupplierInsight.kind(systematic)).isEqualTo(SupplierInsight.Kind.SYSTEMATIC);
        String text = join(SupplierInsight.build(systematic, null, null, null));
        assertThat(text).contains("不是运气问题");
        assertThat(text).contains("能力就在这个水平");
        assertThat(text).contains("工艺要怎么改");
        assertThat(text).doesNotContain("换供应商");
    }

    @Test
    @DisplayName("好坏混着来 → 不稳定：问的是「好的那几批和差的有什么不一样」")
    void 不稳定() {
        SupplierInsight.Profile unstable = p(10, 10, 200, 180, 5, 5, 0.80);
        assertThat(SupplierInsight.kind(unstable)).isEqualTo(SupplierInsight.Kind.UNSTABLE);
        String text = join(SupplierInsight.build(unstable, null, null, null));
        assertThat(text).contains("好坏混着来");
        assertThat(text).contains("做的有什么不一样");
    }

    @Test
    @DisplayName("边界：正好 1/3 算偶发、正好 2/3 算系统性（闭区间，别写成开区间）")
    void 分档边界() {
        // 6 张里 2 张有不良 = 1/3，落在「偶发」这一侧
        assertThat(SupplierInsight.kind(p(6, 6, 120, 110, 4, 2, 0.7)))
                .isEqualTo(SupplierInsight.Kind.SPORADIC);
        // 6 张里 4 张有不良 = 2/3，落在「系统性」这一侧
        assertThat(SupplierInsight.kind(p(6, 6, 120, 110, 2, 4, 0.7)))
                .isEqualTo(SupplierInsight.Kind.SYSTEMATIC);
        // 3 张里 1 张 = 1/3，也是偶发
        assertThat(SupplierInsight.kind(p(3, 3, 60, 58, 2, 1, 0.9)))
                .isEqualTo(SupplierInsight.Kind.SPORADIC);
    }

    // ------------------------------------------------------------------ 不良集中度

    @Test
    @DisplayName("一个不良项过半：先只谈这一项——一次提十条等于没提")
    void 不良过半() {
        String text = join(SupplierInsight.build(p(6, 6, 120, 110, 3, 3, 0.8),
                "外径超差", 0.56, null));
        assertThat(text).contains("外径超差").contains("56.0%");
        assertThat(text).contains("先只谈这一项");
        assertThat(text).contains("提十条等于没提");
    }

    @Test
    @DisplayName("不良分散：占比不到一半就说要去找共同原因")
    void 不良分散() {
        String text = join(SupplierInsight.build(p(6, 6, 120, 110, 3, 3, 0.8),
                "外径超差", 0.22, null));
        assertThat(text).contains("占比没到一半");
        assertThat(text).contains("共同的工序原因");
        assertThat(text).doesNotContain("先只谈这一项");
    }

    // ------------------------------------------------------------------ 趋势

    @Test
    @DisplayName("趋势：月份不够或件数太少都不说话")
    void 趋势样本不足() {
        // 只有三个月：分成 1 个月 vs 2 个月，不叫对比
        assertThat(SupplierInsight.trendNote(List.of(
                new SupplierInsight.MonthPoint("2026-09", 100, 95),
                new SupplierInsight.MonthPoint("2026-10", 100, 90),
                new SupplierInsight.MonthPoint("2026-11", 100, 60)))).isNull();
        // 四个月但后半段只有 10 件：那是两批货的抖动，不是趋势
        assertThat(SupplierInsight.trendNote(List.of(
                new SupplierInsight.MonthPoint("2026-08", 100, 95),
                new SupplierInsight.MonthPoint("2026-09", 100, 95),
                new SupplierInsight.MonthPoint("2026-10", 5, 5),
                new SupplierInsight.MonthPoint("2026-11", 5, 3)))).isNull();
        assertThat(SupplierInsight.trendNote(null)).isNull();
        assertThat(SupplierInsight.trendNote(List.of())).isNull();
    }

    @Test
    @DisplayName("趋势：前后两段按件数加权比，掉 5 个百分点要说「不是运气」")
    void 趋势恶化() {
        String note = SupplierInsight.trendNote(List.of(
                new SupplierInsight.MonthPoint("2026-08", 100, 98),
                new SupplierInsight.MonthPoint("2026-09", 100, 97),
                new SupplierInsight.MonthPoint("2026-10", 100, 90),
                new SupplierInsight.MonthPoint("2026-11", 100, 88)));
        assertThat(note).contains("从 97.5% 掉到 89.0%");
        assertThat(note).contains("降 8.5 个百分点");
        assertThat(note).contains("不是运气问题");
    }

    @Test
    @DisplayName("趋势：改善也要留一句「看是不是筛出来的」")
    void 趋势改善() {
        String note = SupplierInsight.trendNote(List.of(
                new SupplierInsight.MonthPoint("2026-08", 100, 80),
                new SupplierInsight.MonthPoint("2026-09", 100, 82),
                new SupplierInsight.MonthPoint("2026-10", 100, 95),
                new SupplierInsight.MonthPoint("2026-11", 100, 96)));
        assertThat(note).contains("升到");
        // 靠全检筛出来的合格率和靠改工艺拿到的不一样，放松全检就会掉回去
        assertThat(note).contains("靠筛出来");
    }

    @Test
    @DisplayName("趋势：差不到 2 个百分点就是「一直这水平」，别编故事")
    void 趋势持平() {
        String note = SupplierInsight.trendNote(List.of(
                new SupplierInsight.MonthPoint("2026-08", 100, 95),
                new SupplierInsight.MonthPoint("2026-09", 100, 95),
                new SupplierInsight.MonthPoint("2026-10", 100, 96),
                new SupplierInsight.MonthPoint("2026-11", 100, 96)));
        assertThat(note).contains("看不出明显趋势");
        assertThat(note).doesNotContain("掉到").doesNotContain("升到");
        // 整数不带 .0：写着「差 1.0 个百分点」像算了什么很精密的数
        assertThat(note).doesNotContain(".0");
    }

    @Test
    @DisplayName("件数为 0 时合格率是 null，不是 0%——「一件没检」和「全不合格」不是一回事")
    void 零件数() {
        assertThat(p(1, 1, 0, 0, 1, 0, null).yieldRate()).isNull();
        assertThat(p(1, 1, 0, 0, 1, 0, null).badShare()).isEqualTo(0.0 / 1);
        assertThat(p(0, 0, 0, 0, 0, 0, null).badShare()).isNull();
    }

    @Test
    @DisplayName("null 不抛异常")
    void 空值安全() {
        assertThat(SupplierInsight.build(null, "x", 0.5, "y")).isEmpty();
        assertThat(SupplierInsight.kind(null)).isEqualTo(SupplierInsight.Kind.NO_DATA);
    }
}
