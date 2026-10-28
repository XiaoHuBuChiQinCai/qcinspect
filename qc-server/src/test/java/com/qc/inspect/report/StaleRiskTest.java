package com.qc.inspect.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「旧版标准下的判定」值不值得复查。
 *
 * <p>这里唯一要钉死的是<strong>风险方向</strong>：同样是 10 张旧版单，
 * 「收紧后被放行」和「放宽后被拦下」是两件完全不同的事——
 * 前者是合规风险、要抢着查，后者是白花钱、可以慢慢算。
 * 判反了等于把最该看的和最不用看的调了个位置。
 */
class StaleRiskTest {

    private static StaleRisk.Verdict judge(String verdict, long pass, long fail) {
        return StaleRisk.judge(verdict, pass, fail);
    }

    // ==================== 收紧 ====================

    @Test
    @DisplayName("旧版宽松、现行收紧，且有判合格的单：这是唯一要抢着查的一种")
    void tightenedWithPassIsHigh() {
        StaleRisk.Verdict v = judge(StandardDiff.TIGHTENED, 8, 1);
        assertThat(v.level()).isEqualTo(StaleRisk.HIGH);
        assertThat(v.order()).as("排序权重最小，工作台第一行就是它").isZero();
        assertThat(v.advice()).as("要说清做什么，不是复述发生了什么").contains("抽查");
    }

    @Test
    @DisplayName("收紧但旧版下判的全是 NG：已经拦住了，改版不改变结论")
    void tightenedAllFailIsLow() {
        StaleRisk.Verdict v = judge(StandardDiff.TIGHTENED, 0, 5);
        assertThat(v.level()).isEqualTo(StaleRisk.LOW);
        assertThat(v.text()).as("文案不能说成「不受影响」——这批是被拦下的").contains("拦");
    }

    // ==================== 放宽 ====================

    @Test
    @DisplayName("旧版严格、现行放宽，且有判不合格的单：可能是误判，但不算合规风险")
    void relaxedWithFailIsWatchNotHigh() {
        StaleRisk.Verdict v = judge(StandardDiff.RELAXED, 3, 4);
        assertThat(v.level()).isEqualTo(StaleRisk.WATCH);
        assertThat(v.level()).as("放宽不会让放行变危险，不能升成 HIGH").isNotEqualTo(StaleRisk.HIGH);
        assertThat(v.advice()).contains("返工").as("要指出回收成本这件事").isNotNull();
    }

    @Test
    @DisplayName("放宽但旧版下全判合格：放宽不影响结论，不用看")
    void relaxedAllPassIsLow() {
        assertThat(judge(StandardDiff.RELAXED, 6, 0).level()).isEqualTo(StaleRisk.LOW);
    }

    // ==================== 方向不明 ====================

    @Test
    @DisplayName("既有放宽也有收紧：一句话说不清，交给人按项核对")
    void mixedIsWatch() {
        StaleRisk.Verdict v = judge(StandardDiff.MIXED, 2, 2);
        assertThat(v.level()).isEqualTo(StaleRisk.WATCH);
        assertThat(v.advice()).contains("按项核对");
    }

    @Test
    @DisplayName("改的不是宽严（布尔方向反转这类）也要人看，不能当没事")
    void changedIsWatch() {
        assertThat(judge(StandardDiff.CHANGED, 2, 0).level()).isEqualTo(StaleRisk.WATCH);
    }

    @Test
    @DisplayName("两版没实质差异：连看都不用看")
    void neutralIsLow() {
        StaleRisk.Verdict v = judge(StandardDiff.NEUTRAL, 9, 0);
        assertThat(v.level()).isEqualTo(StaleRisk.LOW);
        assertThat(v.text()).isEqualTo("不受影响");
    }

    // ==================== 判不了 ====================

    @Test
    @DisplayName("拿不到改版方向（该产品已无生效版本）：降级成人工核对，不能编一个方向")
    void unknownWhenNoVerdict() {
        StaleRisk.Verdict v = judge(null, 5, 0);
        assertThat(v.level()).isEqualTo(StaleRisk.UNKNOWN);
        assertThat(v.level()).as("不知道方向就绝不能给 HIGH，否则所有旧版单都成了高危").isNotEqualTo(StaleRisk.HIGH);
    }

    @Test
    @DisplayName("空白的方向串按未知处理，不是按「没变化」处理")
    void blankVerdictIsUnknown() {
        assertThat(judge("   ", 1, 0).level()).isEqualTo(StaleRisk.UNKNOWN);
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("零单据：没有风险，但文案不能说成「全部合格」")
    void noInspectionsIsLowWithOwnWording() {
        StaleRisk.Verdict v = judge(StandardDiff.TIGHTENED, 0, 0);
        assertThat(v.level()).isEqualTo(StaleRisk.LOW);
        assertThat(v.text()).isEqualTo("无影响");
        assertThat(v.advice()).as("一张单都没有，说「全部合格」是编的").doesNotContain("全部合格");
    }

    @Test
    @DisplayName("排序权重：HIGH < WATCH < UNKNOWN < LOW，工作台前 8 行不能把该看的挤出去")
    void orderIsMonotonic() {
        int high = judge(StandardDiff.TIGHTENED, 1, 0).order();
        int watch = judge(StandardDiff.MIXED, 1, 0).order();
        int unknown = judge(null, 1, 0).order();
        int low = judge(StandardDiff.NEUTRAL, 1, 0).order();
        assertThat(high).isLessThan(watch);
        assertThat(watch).isLessThan(unknown);
        assertThat(unknown).isLessThan(low);
    }
}
