package com.qc.inspect.spc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 过程能力的纯计算：给定实测值、移动极差和规格限，算出 Cp/Cpk/Pp/Ppk 与判档。
 *
 * <p>为什么单独成类：Cpk 一共有两处要算——SPC 页的「按区间看一道工序」（90 天窗口），
 * 和月报的「按自然月看这个特性有没有退」（一个月窗口）。两处的窗口不同、样本不同，
 * 但 {@code σ̂ = MR̄ / 1.128}、不剔除超规值、判档阈值这些必须是<strong>同一套</strong>。
 * 抄成两份的话，同一天同一个特性会被算出两个 Cpk，然后没人能解释差在哪。
 *
 * <h3>为什么 Cp 和 Pp 分开算</h3>
 * <p>Cp 用组内变异 σ̂（同一张单内部相邻测量的抖动），Pp 用整体标准差 s（把批间漂移
 * 也吃进去）。人机料法都没变、只是批次间有漂移时，会出现 Cp 好看而 Pp 难看的组合——
 * 那不是算错，那是在说「能力够，但没控住」。
 *
 * <h3>为什么不剔除超规值</h3>
 * <p>把超规点剔掉再算 Cpk，等于自己给自己发合格证。过程能力的全部意义就是把
 * 「过程在往外飘」摆到台面上，所以超规值一律计入，只单独报个数。
 */
public final class CapabilityMath {

    /** 移动极差法的 d2 常数：子组容量 n=2 时 d2 = 1.128 */
    public static final double D2 = 1.128;
    /** 单值控制图上下限系数 E2 = 3 / d2 ≈ 2.66 */
    public static final double E2 = 3.0 / D2;
    /** 移动极差图上限系数 D4（n=2） = 3.267 */
    public static final double D4 = 3.267;
    /** 样本量下限。低于这个数算出来的 Cpk 是噪声，不是结论 */
    public static final int MIN_SAMPLE = 25;

    private CapabilityMath() {
    }

    /**
     * @param values  实测值（按录入顺序摊平）
     * @param meanMr  组内移动极差均值；{@code hasMoves=false} 时无意义
     * @param hasMoves 有没有算出移动极差。整批每张单只测一个点时没有，
     *                 这时 σ̂ 退回用整体标准差估计，Cp 与 Pp 会相等
     * @param lsl     规格下限；单边规格时为 null
     * @param usl     规格上限；单边规格时为 null
     * @return 少于 2 个实测值或规格上下限都缺时返回 null——这两种情况算不出能力，
     *         让调用方去决定报错还是跳过，别在这里编一个数出来
     */
    public static Result compute(List<Double> values, double meanMr, boolean hasMoves,
                                 Double lsl, Double usl) {
        if (values == null || values.size() < 2) {
            return null;
        }
        int n = values.size();
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / n;

        double ss = 0;
        for (double v : values) {
            ss += (v - mean) * (v - mean);
        }
        double sOverall = Math.sqrt(ss / (n - 1));
        double sigmaWithin = hasMoves ? meanMr / D2 : sOverall;

        Double cp = (lsl == null || usl == null) ? null : ratio(usl - lsl, 6 * sigmaWithin);
        Double pp = (lsl == null || usl == null) ? null : ratio(usl - lsl, 6 * sOverall);

        // 单边规格时缺的那一侧取 +∞：
        //   只有上限 → min(+∞, USL-mean) = USL-mean，取到有边那侧；
        //   只有下限 → min(mean-LSL, +∞) = mean-LSL。
        // 反了（取 -∞）会让每一侧都被判成「无定义」，单边规格的项永远算不出 Cpk，
        // 而提示里还写着「只看 Cpk/Ppk」——这条真踩过，单测锁在 singleSidedSpec
        double lo = lsl == null ? Double.POSITIVE_INFINITY : mean - lsl;
        double hi = usl == null ? Double.POSITIVE_INFINITY : usl - mean;
        double half = Math.min(lo, hi);
        Double cpk = Double.isInfinite(half) ? null : ratio(half, 3 * sigmaWithin);
        Double ppk = Double.isInfinite(half) ? null : ratio(half, 3 * sOverall);

        int outOfSpec = 0;
        for (double v : values) {
            if ((lsl != null && v < lsl) || (usl != null && v > usl)) {
                outOfSpec++;
            }
        }

        // Cpk 有就用 Cpk：它排除了批间漂移，是「工序本身能不能干」的答案；
        // 没有（连单边规格都没有）才退到 Ppk，只在提示里说清两者不是一回事
        Judge judge = judge(cpk != null ? cpk : ppk);

        return new Result(n, outOfSpec, mean, sigmaWithin, sOverall,
                cp, cpk, pp, ppk, judge.code(), judge.text());
    }

    /**
     * 按 GB/T 4091 的常用分档判档。边界取「达到即入该档」：
     * 1.67 是优秀、1.33 是充足、1.00 是勉强，低于 1.00 为不足。
     *
     * <p>阈值单独成方法是为了能直接测边界值——用构造数据去凑出恰好 1.33 的 Cpk
     * 几乎不可能，测不到边界的阈值等于没锁。
     */
    public static Judge judge(Double v) {
        if (v == null) {
            return new Judge("UNKNOWN", "无法判定");
        }
        if (v >= 1.67) {
            return new Judge("EXCELLENT", "过程能力优秀");
        }
        if (v >= 1.33) {
            return new Judge("SUFFICIENT", "过程能力充足");
        }
        if (v >= 1.00) {
            return new Judge("MARGINAL", "过程能力勉强");
        }
        return new Judge("INSUFFICIENT", "过程能力不足");
    }

    /** 分母为 0 或负（数据几乎没有波动）时不给数，别返回 Infinity 让前端画出个 NULL */
    static Double ratio(double numerator, double denominator) {
        if (denominator <= 0 || Double.isNaN(denominator) || Double.isInfinite(denominator)) {
            return null;
        }
        return numerator / denominator;
    }

    /** 保留指定位数。用 BigDecimal 而不是 Math.round，避免 0.5 在二进制里不是 0.5 引起的尾差 */
    public static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * @param n            实测值个数
     * @param outOfSpec    超出规格的实测值个数（已计入统计，未剔除）
     * @param mean         均值
     * @param sigmaWithin  组内标准差 σ̂
     * @param sigmaOverall 整体标准差 s
     * @param cp           双侧规格才有；单边规格为 null
     * @param cpk          相对最近的规格限；连单边规格都没有时 null
     * @param verdict      EXCELLENT / SUFFICIENT / MARGINAL / INSUFFICIENT / UNKNOWN
     */
    public record Result(int n, int outOfSpec, double mean, double sigmaWithin, double sigmaOverall,
                         Double cp, Double cpk, Double pp, Double ppk,
                         String verdict, String verdictText) {
    }

    /** 判档结果。字段叫 code/text 不叫 verdict——不能和 {@link Result#verdict()} 撞名 */
    public record Judge(String code, String text) {
    }
}
