package com.qc.inspect.msa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * GR&amp;R 均值-极差法。
 *
 * <p>断言分两类。一类是**手算能对上的数**：两边完全相同的评价人、每格极差一样的数据，
 * 期望值能手推出来（EV = R̄×K1、PV = Rp×K3），用来锁「公式没写反、系数没拿错」。
 * 另一类是**不变量**：%GRR² + %PV² 必须等于 100²（变差分解成两个直角边），
 * 这条不看具体数字，只看分解关系对不对——公式抄错时它最先挂。
 *
 * <p>阈值边界只能测函数本身（{@link GrrStudy#gradeOf}），靠造数据去逼近 10.00%
 * 只能测出「大概」；而判错一档的后果是「勉强能用」被写成「可接受」。
 */
class GrrStudyTest {

    /** 两人读数完全相同、每格极差 0.02、两件均值差 0.5 */
    private static double[][][] twoByTwo(double d, double gap) {
        double[][][] x = new double[2][2][2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                double c = 10.0 + gap * j;
                x[i][j][0] = round4(c);
                x[i][j][1] = round4(c + d);
            }
        }
        return x;
    }

    /**
     * 3 人 × 3 件 × 2 次：每个人整体偏 0.2，每格极差 0.2，零件均值间隔 0.5。
     *
     * <p>这组数据两头都不干净，正好覆盖「重复性和再现性各占一半」这一段——
     * 现场最常见的其实也是这种，而不是某一头单独爆掉。
     */
    private static double[][][] threeByThree() {
        double[][][] x = new double[3][3][2];
        double[] partBase = {10.0, 10.5, 11.0};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double c = partBase[j] + 0.2 * i;
                x[i][j][0] = round4(c);
                x[i][j][1] = round4(c + 0.2);
            }
        }
        return x;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ==================== 手算能对上的数 ====================

    @Test
    @DisplayName("两人读数一致：再现性被压成 0，只剩重复性")
    void repeatabilityOnly() {
        // R̄ = 0.02 → EV = 0.02 × 0.8862 = 0.017724
        // 两人均值完全相同 → AV 根号内为负 → 按 0 处理
        // Rp = 0.5 → PV = 0.5 × 0.7071 = 0.35355
        GrrStudy.Result r = GrrStudy.compute(twoByTwo(0.02, 0.5), null, null);

        assertThat(r.ev()).isCloseTo(0.0177, within(0.0002));
        assertThat(r.av()).isZero();
        assertThat(r.grr()).isCloseTo(0.0177, within(0.0002));
        assertThat(r.pv()).isCloseTo(0.3536, within(0.0002));
        assertThat(r.tv()).isCloseTo(0.3540, within(0.0005));
        assertThat(r.pctGrr()).isCloseTo(5.01, within(0.05));
        assertThat(r.pctPv()).isCloseTo(99.87, within(0.05));
        assertThat(r.ndc()).isEqualTo(28);
        assertThat(r.verdict()).isEqualTo(GrrStudy.VERDICT_GOOD);
        assertThat(r.verdictText()).contains("可接受");
    }

    @Test
    @DisplayName("AV 被压成 0 时要说出来：不说，人会以为程序坏了")
    void clampedAvIsExplained() {
        GrrStudy.Result r = GrrStudy.compute(twoByTwo(0.02, 0.5), null, null);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("再现性算出来是负数"));
    }

    @Test
    @DisplayName("重复性与再现性各占一半：两头的建议都要给到")
    void bothSides() {
        GrrStudy.Result r = GrrStudy.compute(threeByThree(), null, null);

        // R̄=0.2 → EV=0.17724；X̄diff=0.4 → AV=√((0.4×0.5231)² − EV²/6)=0.19633
        assertThat(r.ev()).isCloseTo(0.1772, within(0.0005));
        assertThat(r.av()).isCloseTo(0.1963, within(0.0005));
        assertThat(r.grr()).isCloseTo(0.2645, within(0.0005));
        assertThat(r.pv()).isCloseTo(0.5231, within(0.0005));
        assertThat(r.pctGrr()).isCloseTo(45.12, within(0.1));
        assertThat(r.verdict()).isEqualTo(GrrStudy.VERDICT_BAD);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("各占一半"));
    }

    @Test
    @DisplayName("每人均值与平均极差、每件均值与件内差：页面上那张表就靠它")
    void detailArrays() {
        GrrStudy.Result r = GrrStudy.compute(threeByThree(), null, null);

        assertThat(r.appraiserMean()).hasSize(3);
        assertThat(r.appraiserMean()).containsExactly(10.6, 10.8, 11.0);
        assertThat(r.appraiserRange()).containsExactly(0.2, 0.2, 0.2);

        assertThat(r.partMean()).hasSize(3);
        assertThat(r.partMean()).containsExactly(10.3, 10.8, 11.3);
        // 同一件被三个人测出来差多少——这就是再现性在那件上的样子
        assertThat(r.partRange()).containsExactly(0.4, 0.4, 0.4);
    }

    @Test
    @DisplayName("变差分解：%GRR² + %PV² 必须等于 100²")
    void decompositionInvariant() {
        for (double[][][] data : java.util.List.of(twoByTwo(0.02, 0.5), threeByThree())) {
            GrrStudy.Result r = GrrStudy.compute(data, null, null);
            double sum = r.pctGrr() * r.pctGrr() + r.pctPv() * r.pctPv();
            assertThat(sum).isCloseTo(10000, within(5.0));
        }
    }

    // ==================== 阈值边界 ====================

    @Test
    @DisplayName("阈值 10 取闭区间：10.00% 算可接受，10.01% 就是有条件接受")
    void gradeLowerBound() {
        assertThat(GrrStudy.gradeOf(9.99)).isEqualTo(GrrStudy.VERDICT_GOOD);
        assertThat(GrrStudy.gradeOf(10.00)).isEqualTo(GrrStudy.VERDICT_GOOD);
        assertThat(GrrStudy.gradeOf(10.01)).isEqualTo(GrrStudy.VERDICT_MARGINAL);
    }

    @Test
    @DisplayName("阈值 30 取闭区间：30.00% 还算有条件接受，30.01% 不可接受")
    void gradeUpperBound() {
        assertThat(GrrStudy.gradeOf(30.00)).isEqualTo(GrrStudy.VERDICT_MARGINAL);
        assertThat(GrrStudy.gradeOf(30.01)).isEqualTo(GrrStudy.VERDICT_BAD);
    }

    @Test
    @DisplayName("三档的短标签：用在句子里，不带那句长解释")
    void gradeLabels() {
        assertThat(GrrStudy.gradeLabel(GrrStudy.VERDICT_GOOD)).isEqualTo("可接受");
        assertThat(GrrStudy.gradeLabel(GrrStudy.VERDICT_MARGINAL)).isEqualTo("有条件接受");
        assertThat(GrrStudy.gradeLabel(GrrStudy.VERDICT_BAD)).isEqualTo("不可接受");
        assertThat(GrrStudy.gradeLabel("WHATEVER")).isEqualTo("算不出");
    }

    // ==================== 结论会指向不同的下一步 ====================

    @Test
    @DisplayName("再现性占大头：先统一定位基准，换量具解决不了")
    void reproducibilityDominates() {
        // 每人整体偏 0.6，但自己反复测只差 0.01 —— 典型的「手法不一致」而不是尺不行
        double[][][] x = new double[3][3][2];
        double[] base = {10.0, 10.5, 11.0};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double c = base[j] + 0.6 * i;
                x[i][j][0] = round4(c);
                x[i][j][1] = round4(c + 0.01);
            }
        }
        GrrStudy.Result r = GrrStudy.compute(x, null, null);

        assertThat(r.verdict()).isEqualTo(GrrStudy.VERDICT_BAD);
        assertThat(r.pctAv()).isGreaterThan(r.pctEv());
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("再现性占大头"));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("换量具不解决问题"));
    }

    @Test
    @DisplayName("重复性占大头：先查量具分辨力和装夹，换人不解决问题")
    void repeatabilityDominates() {
        // 两个人测同一件差得一模一样，但每人自己测三次能差 0.6
        double[][][] x = new double[2][3][2];
        double[] base = {10.0, 10.5, 11.0};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 3; j++) {
                x[i][j][0] = round4(base[j]);
                x[i][j][1] = round4(base[j] + 0.6);
            }
        }
        GrrStudy.Result r = GrrStudy.compute(x, null, null);

        assertThat(r.verdict()).isEqualTo(GrrStudy.VERDICT_BAD);
        assertThat(r.pctEv()).isGreaterThan(r.pctAv());
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("重复性占大头"));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("换人不解决问题"));
    }

    // ==================== 数据本身可不可信 ====================

    @Test
    @DisplayName("所有读数一字不差：判 UNKNOWN，不判合格")
    void allIdentical() {
        double[][][] x = new double[3][3][2];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                x[i][j][0] = 10.0;
                x[i][j][1] = 10.0;
            }
        }
        GrrStudy.Result r = GrrStudy.compute(x, null, null);

        assertThat(r.tv()).isZero();
        assertThat(r.verdict()).isEqualTo(GrrStudy.VERDICT_UNKNOWN);
        assertThat(r.verdictText()).contains("一字不差");
        assertThat(r.ndc()).isZero();
    }

    @Test
    @DisplayName("某一格三次读数照抄：会把 %GRR 算小，必须点名")
    void copiedReadingIsFlagged() {
        double[][][] x = threeByThree();
        // 1 号评价人测 2 号零件：三次全填同一个数
        x[0][1][0] = 10.6;
        x[0][1][1] = 10.6;
        GrrStudy.Result r = GrrStudy.compute(x, null, null);

        assertThat(r.notes()).anySatisfy(n -> {
            assertThat(n).contains("一字不差");
            assertThat(n).contains("照着第一遍抄");
        });
    }

    @Test
    @DisplayName("零件太少要说明白：少于 5 件 PV 估不准")
    void fewPartsWarned() {
        GrrStudy.Result r = GrrStudy.compute(twoByTwo(0.02, 0.5), null, null);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("零件只有 2 件"));
    }

    @Test
    @DisplayName("ndc 不足 5 级：这个测量系统分不出足够细的档")
    void lowNdcWarned() {
        GrrStudy.Result r = GrrStudy.compute(threeByThree(), null, null);
        assertThat(r.ndc()).isEqualTo(2);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("ndc = 2"));
    }

    @Test
    @DisplayName("公差口径与过程变差口径打架时，把两个数都摆出来，不许挑好看的")
    void toleranceMismatchExplained() {
        // 按过程变差 45% 属不可接受；公差带宽 4.6 时按公差算只有 5.7%，属可接受
        GrrStudy.Result r = GrrStudy.compute(threeByThree(), 9.0, 13.6);

        assertThat(r.verdict()).isEqualTo(GrrStudy.VERDICT_BAD);
        assertThat(r.pctGrrTol()).isNotNull();
        assertThat(r.pctGrrTol()).isCloseTo(5.75, within(0.1));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("两个口径结论不一致"));
    }

    @Test
    @DisplayName("只给一边规格限不算公差口径：算不出来就别装作有")
    void halfToleranceIgnored() {
        assertThat(GrrStudy.compute(threeByThree(), 9.0, null).pctGrrTol()).isNull();
        assertThat(GrrStudy.compute(threeByThree(), null, 13.6).pctGrrTol()).isNull();
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("结构与系数表对齐：2~5 人、2~10 件、2~3 次")
    void shapeRange() {
        assertThat(GrrStudy.checkShape(2, 10, 3).ok()).isTrue();
        assertThat(GrrStudy.checkShape(1, 10, 3).ok()).isFalse();
        assertThat(GrrStudy.checkShape(6, 10, 3).ok()).isFalse();
        assertThat(GrrStudy.checkShape(3, 1, 3).ok()).isFalse();
        assertThat(GrrStudy.checkShape(3, 11, 3).ok()).isFalse();
        assertThat(GrrStudy.checkShape(3, 10, 1).ok()).isFalse();
        assertThat(GrrStudy.checkShape(3, 10, 4).ok()).isFalse();
    }

    @Test
    @DisplayName("读数里混进 NaN / 超大值：拦住，别让它变成一页 NaN%")
    void badReadingsRejected() {
        double[][][] x = twoByTwo(0.02, 0.5);
        x[1][0][1] = Double.NaN;
        assertThat(GrrStudy.checkReadings(x).ok()).isFalse();
        assertThat(GrrStudy.checkReadings(x).message()).contains("不是有效数字");

        double[][][] y = twoByTwo(0.02, 0.5);
        y[0][1][0] = 1.0E9;
        assertThat(GrrStudy.checkReadings(y).ok()).isFalse();
        assertThat(GrrStudy.checkReadings(y).message()).contains("小数点");
    }
}
