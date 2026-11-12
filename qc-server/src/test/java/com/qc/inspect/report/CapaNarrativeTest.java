package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月报里「纠正措施」的结论句。
 *
 * <p>这段文字是管理评审上会被逐句念出来的，所以两类断言最要紧：
 * 一是「没说话」不能变成「看着还行」（本月零措施、有 47 件不良，不能只说「没有立项措施」）；
 * 二是**绝不能出现「验证通过率」**——一挂上这个数，验证人就不判无效了，
 * 而判无效是这套机制唯一防得住「表上全是已验证有效、问题照旧发生」的东西。
 */
@DisplayName("月报 · 纠正措施结论")
class CapaNarrativeTest {

    private static CapaNarrative.Summary s(long opened, long closed, long rejected,
                                           long cancelled, long openNow, long overdue,
                                           int maxDaysOpen, Double avgCloseDays) {
        return new CapaNarrative.Summary(opened, closed, rejected, cancelled,
                openNow, overdue, maxDaysOpen, avgCloseDays);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("零措施 + 有不良：必须点出「问题没人改」，不能只说没立项")
    void 有不良却零措施() {
        String text = join(CapaNarrative.build(s(0, 0, 0, 0, 0, 0, 0, null), 47, null, 0));
        // 只说「本月没有立项纠正措施」是一句记录；要让人站起来的是后面那半截
        assertThat(text).contains("47 件不良");
        assertThat(text).contains("没有立项任何纠正措施");
        assertThat(text).contains("问题本身没有被人改过");
        // 没有不良的时候不该冒出「47 件」
        assertThat(join(CapaNarrative.build(s(0, 0, 0, 0, 0, 0, 0, null), 0, null, 0)))
                .doesNotContain("件不良")
                .contains("也没有不良记录");
    }

    @Test
    @DisplayName("零措施且无不良：一句话收尾，不硬凑")
    void 全空无不良() {
        List<String> lines = CapaNarrative.build(s(0, 0, 0, 0, 0, 0, 0, null), 0, null, 0);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("本月没有立项纠正措施，也没有不良记录。");
    }

    @Test
    @DisplayName("新立与关闭分开说：合成一个「完成率」就谁也看不出是没人立还是立了没关")
    void 流量分开说() {
        String text = join(CapaNarrative.build(s(5, 3, 0, 0, 4, 0, 0, 6.0), 12, null, 0));
        assertThat(text).contains("本月新立 5 条");
        assertThat(text).contains("关闭 3 条");
        assertThat(text).contains("仍有 4 条在改");
    }

    @Test
    @DisplayName("有存量却一条没关：要单说一句，否则埋在小句里像是「没什么事」")
    void 一条没关要说出来() {
        String text = join(CapaNarrative.build(s(2, 0, 0, 0, 6, 0, 30, null), 10, null, 0));
        assertThat(text).contains("本月没有关闭任何一条");
        assertThat(text).contains("这个月一条都没关上");
        assertThat(text).contains("没人再问起");

        // 手上也没有存量时不要说这句——那是「本来就没事」，不是「没人催」
        assertThat(join(CapaNarrative.build(s(0, 0, 0, 0, 0, 0, 0, null), 5, null, 0)))
                .doesNotContain("没人再问起");
    }

    @Test
    @DisplayName("判无效是正常路径：文案必须站在「这是好事」那边")
    void 判无效不写成失败() {
        String text = join(CapaNarrative.build(s(3, 2, 1, 0, 2, 0, 10, 5.0), 8, null, 0));
        assertThat(text).contains("1 条验证判为无效");
        assertThat(text).contains("这不是坏消息");
        assertThat(text).contains("拿数据判");
        // 一旦写成「失败 / 不合格 / 问题严重」，下个月就没人敢判了
        assertThat(text).doesNotContain("失败").doesNotContain("不合格");
    }

    @Test
    @DisplayName("超期带上最久挂了几天，并且指出「挂着就是放弃了但没写理由」")
    void 超期指向真实原因() {
        String text = join(CapaNarrative.build(s(0, 0, 0, 0, 4, 2, 96, null), 5, null, 0));
        assertThat(text).contains("2 条已过要求完成日");
        assertThat(text).contains("挂了 96 天");
        assertThat(text).contains("没人再催");
        // 没超期时不该出现这句
        assertThat(join(CapaNarrative.build(s(1, 0, 0, 0, 1, 0, 3, null), 5, null, 0)))
                .doesNotContain("已过要求完成日");
    }

    @Test
    @DisplayName("平均关闭天数：null 不说成「平均 0 天」，整数不带 .0")
    void 平均天数() {
        assertThat(join(CapaNarrative.build(s(2, 2, 0, 0, 0, 0, 0, 12.0), 4, null, 0)))
                .contains("平均用了 12 天")
                .doesNotContain("12.0");
        assertThat(join(CapaNarrative.build(s(2, 2, 0, 0, 0, 0, 0, 4.5), 4, null, 0)))
                .contains("平均用了 4.5 天");
        // 本月一条都没关，avg 是 null：不能落成 0
        assertThat(join(CapaNarrative.build(s(2, 0, 0, 0, 2, 0, 0, null), 4, null, 0)))
                .doesNotContain("平均用了");
    }

    @Test
    @DisplayName("最慢的一条点名：平均值会被三五天的拉平，看不出有两条挂了半年")
    void 最慢的点名() {
        String text = join(CapaNarrative.build(s(1, 3, 0, 0, 0, 0, 0, 20.5),
                6, "夹具定位面磨损", 182));
        assertThat(text).contains("最慢的一条「夹具定位面磨损」拖了 182 天");
        // 没有慢的就不该硬编一句「最慢的一条」
        assertThat(join(CapaNarrative.build(s(1, 1, 0, 0, 0, 0, 0, 3.0), 2, null, 0)))
                .doesNotContain("最慢的一条");
        assertThat(join(CapaNarrative.build(s(1, 1, 0, 0, 0, 0, 0, 3.0), 2, "  ", 0)))
                .doesNotContain("最慢的一条");
    }

    @Test
    @DisplayName("撤销不写成事故：撤掉比挂着假装在改要好")
    void 撤销的说法() {
        String text = join(CapaNarrative.build(s(1, 0, 0, 2, 1, 0, 5, null), 3, null, 0));
        assertThat(text).contains("本月撤销 2 条");
        assertThat(text).contains("比让它挂在那里假装在改要好");
    }

    @Test
    @DisplayName("任何组合都不许出现「验证通过率」——刻意的取舍，测试守住它")
    void 永不出现通过率() {
        CapaNarrative.Summary[] all = {
                s(0, 0, 0, 0, 0, 0, 0, null),
                s(5, 3, 1, 1, 4, 2, 60, 9.5),
                s(0, 0, 3, 0, 10, 8, 200, null),
                s(2, 2, 0, 0, 0, 0, 0, 1.0),
        };
        for (CapaNarrative.Summary x : all) {
            String text = join(CapaNarrative.build(x, 30, "某条拖很久的", 100));
            assertThat(text).doesNotContain("通过率");
            assertThat(text).doesNotContain("有效率");
            assertThat(text).doesNotContain("达标率");
        }
    }

    @Test
    @DisplayName("null 不抛异常，返回空列表（月报其他段照常出）")
    void 空值安全() {
        assertThat(CapaNarrative.build(null, 0, null, 0)).isEmpty();
    }

    @Test
    @DisplayName("数字是 0 而不是负数：撤销/超期都按 count 出，负数只可能来自算错")
    void 不带负号() {
        String text = join(CapaNarrative.build(s(1, 1, 0, 0, 0, 0, 0, 2.0), 4, null, 0));
        assertThat(text).doesNotContain("-");
    }
}
