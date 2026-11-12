package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 月报的「过程能力」结论。
 *
 * <p>这段文字的价值全在「哪几项在往下走」这一句上，所以测试重点不是措辞，而是：
 * <ul>
 *   <li>样本不够时不许下结论——样本少的时候 Cpk 自己就在抖，拿它判工序是在冤枉人；</li>
 *   <li>环比只在两边都够样本、规格没变时才比——否则一期的噪声会被读成趋势；</li>
 *   <li>结论里永远不出现「平均 Cpk」——公差宽的特性会把平均拉上去，正好盖住出问题的那项。</li>
 * </ul>
 */
@DisplayName("月报过程能力结论文本 CapabilityNarrative")
class CapabilityNarrativeTest {

    private static CapabilityNarrative.Item item(String label, int n, Double cpk) {
        return new CapabilityNarrative.Item(label, n, cpk, null, 0);
    }

    private static CapabilityNarrative.Item item(String label, int n, Double cpk,
                                                 Double prevCpk, int prevN) {
        return new CapabilityNarrative.Item(label, n, cpk, prevCpk, prevN);
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("一项都没有：说清为什么算不出，而不是留一段空白")
    void emptySaysWhy() {
        String text = join(CapabilityNarrative.build(List.of(), 0));

        assertThat(text).contains("没有可评过程能力的特性")
                .contains("规格上下限")
                .as("要让人知道不是「没问题」，是「没判过」")
                .contains("谈不上能力");
        assertThat(text).doesNotContain("能力不足");
    }

    @Test
    @DisplayName("全都不够样本：不给判档，并说清 25 个这条线")
    void allFewSamplesRefusesToJudge() {
        List<CapabilityNarrative.Item> items = List.of(
                item("壳体 · 外径", 8, 0.86),
                item("壳体 · 同轴度", 12, 1.05));

        String text = join(CapabilityNarrative.build(items, 0));

        assertThat(text).contains("不到 25 个").contains("不判过程能力");
        assertThat(text).as("样本不足时不许说「能力不足」——那是拿噪声判工序")
                .doesNotContain("能力不足（Cpk");
        assertThat(text).as("要说清最多的那项有几个样本，人才知道差多少").contains("12");
    }

    @Test
    @DisplayName("能力不足的项要点名，并且把话说在工艺上而不是检验上")
    void insufficientPointsAtProcess() {
        String text = join(CapabilityNarrative.build(
                List.of(item("壳体 · 外径", 40, 0.86), item("壳体 · 同轴度", 40, 1.62)), 0));

        assertThat(text).contains("能力不足（Cpk < 1.00）")
                .as("要带上是哪一项、差到什么程度").contains("壳体 · 外径（0.86）");
        assertThat(text).as("点名之后必须给方向")
                .contains("工艺").contains("加严检验");
        assertThat(text).as("充足的项不该被拎进不足的句子里").doesNotContain("同轴度（1.62）");
    }

    @Test
    @DisplayName("下滑句同时给出前后两个数，能看出掉多少")
    void slippingShowsBothSides() {
        String text = join(CapabilityNarrative.build(
                List.of(item("壳体 · 外径", 40, 1.05, 1.42, 40)), 0));

        assertThat(text).contains("明显下滑")
                .as("1.42→1.05 这种写法比「下降 0.37」更容易一眼看懂")
                .contains("1.42→1.05");
        assertThat(text).as("下滑还没变成不良，要说明为什么现在就得管")
                .contains("还没变成不良");
    }

    @Test
    @DisplayName("下滑 0.29 不报、0.30 报：阈值边界不能靠感觉")
    void slipThresholdIsExact() {
        assertThat(CapabilityNarrative.isSlipping(item("a", 40, 1.13, 1.42, 40)))
                .as("掉 0.29 还在抽样噪声范围里").isFalse();
        assertThat(CapabilityNarrative.isSlipping(item("a", 40, 1.12, 1.42, 40)))
                .as("掉 0.30 就该报").isTrue();
        assertThat(CapabilityNarrative.isSlipping(item("a", 40, 1.42, 1.12, 40)))
                .as("变好不是下滑").isFalse();
    }

    @Test
    @DisplayName("上月样本不足时不算下滑，哪怕数字掉得很凶")
    void prevFewSamplesIsNotComparable() {
        assertThat(CapabilityNarrative.isSlipping(item("a", 40, 0.80, 2.00, 6)))
                .as("上月的 2.00 是 6 个样本算的，不能当成基准").isFalse();

        String text = join(CapabilityNarrative.build(
                List.of(item("壳体 · 外径", 40, 0.80, 2.00, 6)), 0));
        assertThat(text).doesNotContain("明显下滑");
    }

    @Test
    @DisplayName("本月样本不足时同样不判，也不参与能力不足的名单")
    void curFewSamplesIsNotJudged() {
        String text = join(CapabilityNarrative.build(
                List.of(item("壳体 · 外径", 6, 0.70), item("壳体 · 台阶", 40, 1.50)), 0));

        assertThat(text).doesNotContain("能力不足（Cpk < 1.00）");
        assertThat(text).contains("另有 1 项本月样本不足 25 个");
    }

    @Test
    @DisplayName("1.00~1.33 单独说「勉强」，1.33 恰好算充足")
    void marginalBandBoundaries() {
        String text = join(CapabilityNarrative.build(
                List.of(item("a · 外径", 40, 1.33), item("a · 壁厚", 40, 1.00)), 0));

        assertThat(text).contains("在 1.00~1.33 之间的还有 1 项");
        assertThat(text).as("1.33 是充足的下界，不该被算进勉强").contains("其余 1 项在 1.33 以上");
        assertThat(text).as("概要只报非零的那一档，别写成「能力不足 0 项」").doesNotContain("能力不足 0 项");
    }

    @Test
    @DisplayName("全部充足时给个明确的好消息，别让人自己从表里推")
    void allGoodSaysSo() {
        String text = join(CapabilityNarrative.build(
                List.of(item("a · 外径", 40, 1.80), item("a · 壁厚", 40, 1.45)), 0));

        assertThat(text).contains("全部在 1.33 以上");
        assertThat(text).doesNotContain("能力不足").doesNotContain("明显下滑");
    }

    @Test
    @DisplayName("充足的一月也要报「有几项判不了」，否则下个月还是没人去补抽检")
    void goodMonthStillReportsUnjudged() {
        String text = join(CapabilityNarrative.build(
                List.of(item("a · 外径", 40, 1.80), item("a · 划痕", 5, 1.90)), 0));

        assertThat(text).contains("全部在 1.33 以上").contains("另有 1 项本月样本不足");
    }

    @Test
    @DisplayName("规格限变过就不比环比，并说明为什么")
    void specChangedSkipsComparison() {
        String text = join(CapabilityNarrative.build(
                List.of(item("a · 外径", 40, 1.20, null, 40)), 2));

        assertThat(text).contains("有 2 项的规格限在这两个月之间变过")
                .as("要说清是分母变了，不是能力变了")
                .contains("分母");
    }

    @Test
    @DisplayName("结论里永远不出现「平均 Cpk」")
    void neverReportsAverage() {
        List<CapabilityNarrative.Item> items = new ArrayList<>();
        items.add(item("a · 外径", 40, 0.72, 1.40, 40));
        items.add(item("a · 壁厚", 40, 2.40, 2.40, 40));
        items.add(item("a · 划痕", 8, 1.90));

        String text = join(CapabilityNarrative.build(items, 1));

        assertThat(text).as("公差宽的特性会把平均拉高，正好盖住 0.72 那一项")
                .doesNotContain("平均");
    }

    @Test
    @DisplayName("够样本却算不出 Cpk：指向量具分辨力，而不是含糊说「样本不足」")
    void flatReadingsPointAtGauge() {
        String text = join(CapabilityNarrative.build(
                List.of(item("a · 硬度", 30, null), item("a · 外径", 40, 1.60)), 0));

        assertThat(text).contains("实测值没有波动").contains("分辨力");
        assertThat(text).as("样本已经够了，再说是样本不足就把人指向了错的地方")
                .doesNotContain("本月样本不足");
        assertThat(text).as("样本不足那句也不该出现 30 这个数").doesNotContain("不到 25 个");
    }

    @Test
    @DisplayName("样本不足与没有波动同时在，两句都要说，且都不进判档分母")
    void fewAndFlatCoexist() {
        String text = join(CapabilityNarrative.build(
                List.of(item("a · 硬度", 30, null), item("a · 壁厚", 6, 1.10), item("a · 外径", 40, 1.20)), 0));

        assertThat(text).contains("本月 1 个特性够样本可判过程能力").as("只有够样本且算得出 Cpk 的才进分母")
                .contains("另有 1 项本月样本不足")
                .contains("有 1 项够样本却算不出 Cpk");
    }

    @Test
    @DisplayName("不足项超过三项时收口成「等 N 项」，一句话塞八个名字没人读")
    void longListIsTruncated() {
        List<CapabilityNarrative.Item> items = List.of(
                item("a · 外径", 40, 0.60),
                item("a · 壁厚", 40, 0.70),
                item("a · 台阶", 40, 0.80),
                item("a · 圆度", 40, 0.90));

        String text = join(CapabilityNarrative.build(items, 0));

        assertThat(text).contains("能力不足 4 项").contains("等 4 项");
        assertThat(text).as("前三个照列，第四个收进「等」").doesNotContain("a · 圆度（0.90）");
    }
}
