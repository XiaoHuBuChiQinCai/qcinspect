package com.qc.inspect.spc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过程能力的纯计算。
 *
 * <p>SPC 页和月报都从这里取数，所以这一处算错，两个页面会一起错、而且错得一致——
 * 反而更难发现（两处对得上，人就不再怀疑了）。因此这里锁死三件事：
 * <ul>
 *   <li>σ̂ 只由组内移动极差估（MR̄ ÷ 1.128），不能偷偷换成整体标准差；</li>
 *   <li>超规值计入统计，不剔除；</li>
 *   <li>波动为 0 时给 null 而不是 Infinity——Infinity 到前端会变成一个空框，没人看得出是算不出来。</li>
 * </ul>
 */
@DisplayName("过程能力计算 CapabilityMath")
class CapabilityMathTest {

    /** 15.1 / 14.9 交替 14 次：组内相邻极差恒为 0.2，均值 15，手算可核 */
    private static List<Double> alternating() {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            v.add(15.1);
            v.add(14.9);
        }
        return v;
    }

    /**
     * 两批之间整体平移 0.5：每张单内部仍是 15.1/14.9 交替（组内极差 0.2），
     * 但两批的均值不同。这种数据才能看出 Cp 与 Pp 的差别——只靠交替一份数据，
     * 整体标准差反而比组内 σ̂ 小。
     */
    private static List<Double> twoBatchesDrifted() {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            v.add(15.1);
            v.add(14.9);
        }
        for (int i = 0; i < 14; i++) {
            v.add(15.6);
            v.add(15.4);
        }
        return v;
    }

    private static List<Double> same(int n, double value) {
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            v.add(value);
        }
        return v;
    }

    @Test
    @DisplayName("σ̂ = MR̄/1.128：Cp 与 Cpk 都按组内变异算")
    void sigmaWithinComesFromMovingRange() {
        double meanMr = 0.2;
        CapabilityMath.Result r = CapabilityMath.compute(alternating(), meanMr, true, 14.0, 16.0);

        assertThat(r).isNotNull();
        assertThat(r.n()).isEqualTo(28);
        assertThat(r.mean()).isEqualTo(15.0);
        assertThat(r.sigmaWithin()).isCloseTo(0.2 / 1.128, org.assertj.core.data.Offset.offset(1e-12));
        // Cp = 2 / (6 × 0.177305) = 1.8800
        assertThat(r.cp()).isCloseTo(1.88, org.assertj.core.data.Offset.offset(0.001));
        assertThat(r.cpk()).isCloseTo(1.88, org.assertj.core.data.Offset.offset(0.001));
        assertThat(r.verdict()).isEqualTo("EXCELLENT");
    }

    @Test
    @DisplayName("Cp 用组内 σ̂、Pp 用整体 s：两者不同才说明批间有漂移")
    void cpAndPpUseDifferentSigmas() {
        CapabilityMath.Result r = CapabilityMath.compute(twoBatchesDrifted(), 0.2, true, 14.0, 16.0);

        assertThat(r.mean()).isEqualTo(15.25);
        assertThat(r.sigmaOverall()).as("批间漂移会进整体标准差，比组内 σ̂ 大")
                .isGreaterThan(r.sigmaWithin());
        assertThat(r.pp()).as("分母变大，Pp 必须比 Cp 小").isLessThan(r.cp());
        // 批均值平移后最近的限变成上限，Cpk 因此低于 Cp——这正是「能力够但没控住」的样子
        assertThat(r.cpk()).as("均值偏离中心，Cpk 必须低于 Cp").isLessThan(r.cp());
        // 均值居中的那份数据里，Cp 与 Cpk 才相等
        CapabilityMath.Result centered = CapabilityMath.compute(alternating(), 0.2, true, 14.0, 16.0);
        assertThat(centered.cpk()).isCloseTo(centered.cp(),
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("超规值计入统计，不剔除")
    void outOfSpecIsCountedNotDropped() {
        List<Double> values = new ArrayList<>(alternating());
        values.addAll(same(10, 16.5));

        CapabilityMath.Result r = CapabilityMath.compute(values, 0.2, true, 14.0, 16.0);

        assertThat(r.n()).as("超规值也在样本里").isEqualTo(38);
        assertThat(r.outOfSpec()).isEqualTo(10);
        CapabilityMath.Result clean = CapabilityMath.compute(alternating(), 0.2, true, 14.0, 16.0);
        assertThat(r.cpk()).as("掺进超规值后 Cpk 必须变差").isLessThan(clean.cpk());
    }

    @Test
    @DisplayName("单边规格：Cp 无定义，只给 Cpk")
    void singleSidedSpec() {
        CapabilityMath.Result r = CapabilityMath.compute(alternating(), 0.2, true, null, 16.0);

        assertThat(r.cp()).as("只有单边规格时 Cp 没有意义").isNull();
        assertThat(r.cpk()).as("单边规格必须算得出 Cpk——取有边的那一侧")
                .isCloseTo(1.88, org.assertj.core.data.Offset.offset(0.001));
        assertThat(r.pp()).isNull();
        assertThat(r.verdict()).as("算得出 Cpk 就要判档，不能一律 UNKNOWN").isEqualTo("EXCELLENT");

        // 只有下限也一样：缺的那侧取 +∞，min 才落得到有边那侧
        CapabilityMath.Result lower = CapabilityMath.compute(alternating(), 0.2, true, 14.0, null);
        assertThat(lower.cpk()).as("只有下限时 Cpk 用 mean-LSL 那一侧")
                .isCloseTo(1.88, org.assertj.core.data.Offset.offset(0.001));
        assertThat(lower.ppk()).as("Ppk 用整体标准差，与 Cpk 不是一回事")
                .isGreaterThan(lower.cpk());
    }

    @Test
    @DisplayName("完全没有规格限：一个能力指数都不给，判 UNKNOWN")
    void noSpecAtAll() {
        CapabilityMath.Result r = CapabilityMath.compute(alternating(), 0.2, true, null, null);

        assertThat(r.cp()).isNull();
        assertThat(r.cpk()).isNull();
        assertThat(r.verdict()).isEqualTo("UNKNOWN");
        assertThat(r.verdictText()).isEqualTo("无法判定");
    }

    @Test
    @DisplayName("波动为 0 时给 null，不给 Infinity")
    void zeroVariationGivesNullNotInfinity() {
        CapabilityMath.Result r = CapabilityMath.compute(same(30, 15.0), 0, true, 14.0, 16.0);

        assertThat(r.cp()).isNull();
        assertThat(r.cpk()).isNull();
        assertThat(r.verdict()).as("算不出来就说算不出来，不能判成优秀").isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("少于两个实测值算不出标准差，返回 null 让调用方去报错")
    void tooFewValues() {
        assertThat(CapabilityMath.compute(List.of(15.0), 0, false, 14.0, 16.0)).isNull();
        assertThat(CapabilityMath.compute(List.of(), 0, false, 14.0, 16.0)).isNull();
        assertThat(CapabilityMath.compute(null, 0, false, 14.0, 16.0)).isNull();
    }

    @Test
    @DisplayName("没有移动极差时退回整体标准差，此时 Cp 与 Pp 必然相等")
    void noMovingRangeFallsBack() {
        CapabilityMath.Result r = CapabilityMath.compute(alternating(), 0, false, 14.0, 16.0);

        assertThat(r.cp()).isCloseTo(r.pp(), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(r.cpk()).isCloseTo(r.ppk(), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("判档边界：1.67 / 1.33 / 1.00 达到即入该档")
    void verdictBoundaries() {
        assertThat(CapabilityMath.judge(1.67).code()).isEqualTo("EXCELLENT");
        assertThat(CapabilityMath.judge(1.669).code()).isEqualTo("SUFFICIENT");
        assertThat(CapabilityMath.judge(1.33).code()).isEqualTo("SUFFICIENT");
        assertThat(CapabilityMath.judge(1.329).code()).isEqualTo("MARGINAL");
        assertThat(CapabilityMath.judge(1.00).code()).isEqualTo("MARGINAL");
        assertThat(CapabilityMath.judge(0.999).code()).isEqualTo("INSUFFICIENT");
        assertThat(CapabilityMath.judge(null).code()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("四舍五入用 BigDecimal：不是 Math.round 那种「看运气」的实现")
    void roundingIsStable() {
        assertThat(CapabilityMath.round(1.2345, 3)).isEqualTo(1.235);
        assertThat(CapabilityMath.round(0.177305, 3)).isEqualTo(0.177);
        assertThat(CapabilityMath.round(2.0, 2)).isEqualTo(2.0);
    }
}
