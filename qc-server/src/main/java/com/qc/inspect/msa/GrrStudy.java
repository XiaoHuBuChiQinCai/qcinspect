package com.qc.inspect.msa;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 量具重复性与再现性（GR&amp;R）研究，均值-极差法。
 *
 * <p>量具台账管的是「校准没过期」，那是时间维度；校准合格只说明这把尺和标准器对得上，
 * 不说明它能分辨出这道公差上的差异。一把分度值 0.05 的卡尺去测 10.00±0.02 的外径，
 * 校准证书照样合格，测出来的数据照样不能用。这一层就是补这个洞。
 *
 * <p>算法用 AIAG MSA 手册第四版的均值-极差法（不做 ANOVA，因为现场要的是能当场
 * 算给人看的数，不是要分解交互作用）：
 * <pre>
 *   EV（重复性）  = R̄ × K1(n)                      同一人同一件反复测的波动
 *   AV（再现性）  = √((X̄diff × K2(a))² − EV²/(b·n))  不同人测同一件的差异
 *   GRR           = √(EV² + AV²)
 *   PV（零件变差）= Rp × K3(b)
 *   TV（总变差）  = √(GRR² + PV²)
 *   %GRR          = 100 × GRR / TV
 * </pre>
 *
 * <p>K1 / K2 / K3 是手册公布的常数（等于 6/d2*），**不要改成运行时从 d2 表反算**：
 * d2* 依赖子组个数，现场照表查的数就是这些，自己算出来的第四位小数会和客户的
 * 手工账对不上，而客户的账要进审核报告。
 *
 * <p>判定阈值：%GRR ≤ 10 可接受；10 &lt; %GRR ≤ 30 有条件接受；&gt; 30 不可接受。
 * 比较用的是**定标到 2 位小数之后的数**——和页面上显示的是同一个值，否则会出现
 * 「页面上写着 10.00%，结论却是有条件接受」这种自相矛盾的画面。
 */
public final class GrrStudy {

    /** 评价人数范围：K2 系数表只给到 5 */
    public static final int MIN_APPRAISERS = 2;
    public static final int MAX_APPRAISERS = 5;

    /** 零件数范围：K3 系数表只给到 10；少于 5 件时 PV 估不准，只提示不拒绝 */
    public static final int MIN_PARTS = 2;
    public static final int MAX_PARTS = 10;

    /** 每格重复次数：K1 系数表只给到 3 */
    public static final int MIN_TRIALS = 2;
    public static final int MAX_TRIALS = 3;

    /** 结论 */
    public static final String VERDICT_GOOD = "GOOD";
    public static final String VERDICT_MARGINAL = "MARGINAL";
    public static final String VERDICT_BAD = "BAD";
    public static final String VERDICT_UNKNOWN = "UNKNOWN";

    /** 单次读数允许的绝对值上限：超过这个数基本是手滑多按了几位，不是量出来的 */
    private static final double MAX_ABS_READING = 1e7;

    /** 手册 K1：重复次数 → 系数 */
    private static final double[] K1 = {0, 0, 0.8862, 0.5908, 0};

    /** 手册 K2：评价人数 → 系数 */
    private static final double[] K2 = {0, 0, 0.7071, 0.5231, 0.4467, 0.4030};

    /** 手册 K3：零件数 → 系数 */
    private static final double[] K3 = {
            0, 0, 0.7071, 0.5231, 0.4467, 0.4030, 0.3742, 0.3534, 0.3375, 0.3249, 0.3146};

    private GrrStudy() {
    }

    /** 校验通过 / 不通过 + 一句给人看的原因 */
    public record Check(boolean ok, String message) {
        /** 与 record 的 ok() 取值方法区分开：同名会被编译器判为重复定义 */
        public static Check pass() {
            return new Check(true, null);
        }

        public static Check fail(String message) {
            return new Check(false, message);
        }
    }

    /**
     * 一次研究的全部结果。
     *
     * <p>明细数组（每人的均值与平均极差、每件的均值与件内差）是给页面画表格用的：
     * 只给一个 %GRR 的话，人知道「不行」但不知道「从哪下手改」。
     */
    public record Result(
            int appraisers, int parts, int trials,
            double mean, double rangeBar,
            double ev, double av, double grr, double pv, double tv,
            double pctEv, double pctAv, double pctGrr, double pctPv,
            Double pctGrrTol,
            int ndc,
            String verdict, String verdictText,
            List<String> notes,
            List<Double> appraiserMean, List<Double> appraiserRange,
            List<Double> partMean, List<Double> partRange) {
    }

    /** 结构校验：范围之外算不出 %GRR，收了也只是个假数字 */
    public static Check checkShape(int appraisers, int parts, int trials) {
        if (appraisers < MIN_APPRAISERS || appraisers > MAX_APPRAISERS) {
            return Check.fail("评价人要 " + MIN_APPRAISERS + "~" + MAX_APPRAISERS + " 个，现在是 " + appraisers + " 个");
        }
        if (parts < MIN_PARTS || parts > MAX_PARTS) {
            return Check.fail("零件要 " + MIN_PARTS + "~" + MAX_PARTS + " 件，现在是 " + parts + " 件");
        }
        if (trials < MIN_TRIALS || trials > MAX_TRIALS) {
            return Check.fail("每件重复测 " + MIN_TRIALS + "~" + MAX_TRIALS + " 次，现在是 " + trials + " 次");
        }
        return Check.pass();
    }

    /**
     * 算一次研究。
     *
     * @param x   读数，x[评价人][零件][试次]。调用方必须先用 {@link #checkShape} 与
     *            {@link #checkReadings} 校验过，这里不再重复判空
     * @param lsl 规格下限，没有给 null
     * @param usl 规格上限，没有给 null
     */
    public static Result compute(double[][][] x, Double lsl, Double usl) {
        int a = x.length;
        int b = x[0].length;
        int n = x[0][0].length;

        // ---- 每一格的均值与极差：极差是重复性的原料，均值是再现性与零件变差的原料
        double[][] cellMean = new double[a][b];
        double[][] cellRange = new double[a][b];
        double sum = 0;
        double rangeSum = 0;
        int cells = 0;
        for (int i = 0; i < a; i++) {
            for (int j = 0; j < b; j++) {
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                double s = 0;
                for (int k = 0; k < n; k++) {
                    double v = x[i][j][k];
                    s += v;
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                cellMean[i][j] = s / n;
                cellRange[i][j] = hi - lo;
                sum += s;
                rangeSum += hi - lo;
                cells++;
            }
        }
        double mean = sum / ((double) cells * n);
        double rangeBar = rangeSum / cells;

        // ---- 重复性
        double ev = rangeBar * K1[n];

        // ---- 再现性：先算每个人的总均值，取极差
        List<Double> appraiserMean = new ArrayList<>(a);
        List<Double> appraiserRange = new ArrayList<>(a);
        double aMin = Double.MAX_VALUE;
        double aMax = -Double.MAX_VALUE;
        for (int i = 0; i < a; i++) {
            double s = 0;
            double r = 0;
            for (int j = 0; j < b; j++) {
                s += cellMean[i][j];
                r += cellRange[i][j];
            }
            double m = s / b;
            appraiserMean.add(round(m, 4));
            appraiserRange.add(round(r / b, 4));
            aMin = Math.min(aMin, m);
            aMax = Math.max(aMax, m);
        }
        double xDiff = aMax - aMin;
        double avInner = Math.pow(xDiff * K2[a], 2) - ev * ev / ((double) b * n);
        boolean avClamped = avInner < 0;
        double av = Math.sqrt(Math.max(0, avInner));

        double grr = Math.hypot(ev, av);

        // ---- 零件变差：每件在所有人所有次上的均值，取极差
        List<Double> partMean = new ArrayList<>(b);
        List<Double> partRange = new ArrayList<>(b);
        double pMin = Double.MAX_VALUE;
        double pMax = -Double.MAX_VALUE;
        for (int j = 0; j < b; j++) {
            double s = 0;
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (int i = 0; i < a; i++) {
                s += cellMean[i][j];
                lo = Math.min(lo, cellMean[i][j]);
                hi = Math.max(hi, cellMean[i][j]);
            }
            double m = s / a;
            partMean.add(round(m, 4));
            partRange.add(round(hi - lo, 4));
            pMin = Math.min(pMin, m);
            pMax = Math.max(pMax, m);
        }
        double pv = (pMax - pMin) * K3[b];
        double tv = Math.hypot(grr, pv);

        List<String> notes = new ArrayList<>();

        // ---- 全是同一个数：没有任何变差可分解
        if (tv <= 0) {
            return new Result(a, b, n,
                    round(mean, 4), round(rangeBar, 4),
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, null, 0,
                    VERDICT_UNKNOWN,
                    "所有读数一字不差，算不出变差。先确认量具的分辨力够不够这道公差，再看是不是所有人"
                            + "都只测了同一件（或者读数照着第一遍抄的）",
                    notes, appraiserMean, appraiserRange, partMean, partRange);
        }

        double pctEv = round(100 * ev / tv, 2);
        double pctAv = round(100 * av / tv, 2);
        double pctGrr = round(100 * grr / tv, 2);
        double pctPv = round(100 * pv / tv, 2);

        // ---- ndc：可区分的类别数，AIAG 要求 ≥ 5
        int ndc;
        if (grr <= 0) {
            ndc = 99;
            notes.add("GRR 算出来是 0（所有重复读数完全相同），可区分类别数给不到准数，按 99 显示");
        } else {
            ndc = (int) Math.floor(1.41 * pv / grr);
        }

        // ---- 按公差算的 %GRR：客户图纸上写的是公差，不是过程变差
        Double pctGrrTol = null;
        if (lsl != null && usl != null && usl > lsl) {
            pctGrrTol = round(100 * grr / (usl - lsl), 2);
        }

        String verdict = gradeOf(pctGrr);
        String verdictText = verdictText(pctGrr, verdict);

        // ---- 数据可信度提示：这些不改变结论，但会决定结论该怎么看
        if (b < 5) {
            notes.add("零件只有 " + b + " 件（AIAG 建议 10 件），零件变差 PV 估不准，%GRR 只能当参考");
        }
        if (b < MAX_PARTS) {
            notes.add("取样的零件要覆盖公差带，别全挑同一批刚下线的——挑得太集中会把 %GRR 算得虚高");
        }
        if (avClamped) {
            notes.add("再现性算出来是负数（评价人之间的差异比重复测量波动还小），按 0 处理。"
                    + "这是手册认可的做法，不是算法出错");
        }
        if (ndc < 5) {
            notes.add("ndc = " + ndc + "，只能分出 " + ndc + " 个层级（AIAG 要求至少 5 级）："
                    + "这样的测量系统没法把过程分成足够细的档");
        }
        for (int i = 0; i < a; i++) {
            for (int j = 0; j < b; j++) {
                if (allSame(x[i][j])) {
                    notes.add((i + 1) + " 号评价人测 " + (j + 1) + " 号零件的 " + n
                            + " 次读数一字不差，现场很可能是照着第一遍抄的；照抄的读数会把 %GRR 算小，"
                            + "评审时这种数据要单独问一句");
                }
            }
        }
        if (pctGrrTol != null && !gradeOf(pctGrrTol).equals(verdict)) {
            notes.add("两个口径结论不一致：按过程变差算 " + pctGrr + "%（" + gradeLabel(verdict)
                    + "），按公差算 " + pctGrrTol + "%（" + gradeLabel(gradeOf(pctGrrTol))
                    + "）。评审时按客户或图纸要求选一个口径，别两边挑好看的那个用");
        }
        String advice = advice(pctEv, pctAv, verdict);
        if (advice != null) {
            notes.add(advice);
        }

        return new Result(a, b, n,
                round(mean, 4), round(rangeBar, 4),
                round(ev, 4), round(av, 4), round(grr, 4), round(pv, 4), round(tv, 4),
                pctEv, pctAv, pctGrr, pctPv, pctGrrTol, ndc,
                verdict, verdictText, notes,
                appraiserMean, appraiserRange, partMean, partRange);
    }

    /**
     * 读数逐格校验。
     *
     * <p>JSON 里 null、NaN、空数组都会在服务层被解析成「这一格没填」。这里拦的是
     * 解析之后还想混进来的脏值——数值型字段一旦是 NaN/Infinity，后面所有开方
     * 都会安静地变成 NaN，页面上显示一片「NaN%」，比报错难查得多。
     */
    public static Check checkReadings(double[][][] x) {
        if (x == null || x.length == 0) {
            return Check.fail("没有读数");
        }
        for (int i = 0; i < x.length; i++) {
            if (x[i] == null || x[i].length == 0) {
                return Check.fail((i + 1) + " 号评价人没有读数");
            }
            for (int j = 0; j < x[i].length; j++) {
                if (x[i][j] == null || x[i][j].length == 0) {
                    return Check.fail((i + 1) + " 号评价人测 " + (j + 1) + " 号零件这一格没填");
                }
                for (int k = 0; k < x[i][j].length; k++) {
                    double v = x[i][j][k];
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        return Check.fail((i + 1) + " 号评价人测 " + (j + 1) + " 号零件第 " + (k + 1) + " 次不是有效数字");
                    }
                    if (Math.abs(v) > MAX_ABS_READING) {
                        return Check.fail((i + 1) + " 号评价人测 " + (j + 1) + " 号零件第 " + (k + 1)
                                + " 次的读数 " + v + " 不像量出来的，检查一下小数点");
                    }
                }
            }
        }
        return Check.pass();
    }

    private static boolean allSame(double[] vals) {
        for (int k = 1; k < vals.length; k++) {
            if (Double.compare(vals[k], vals[0]) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按 %GRR 定档。阈值 10 / 30 取闭区间——「正好 10.00%」算可接受，
     * 因为手册写的是 ≤10%，而且页面上显示的也是 10.00%，不能自相矛盾。
     *
     * <p>公开出来是为了能精确测边界：靠造数据去逼近 10.00% 只能测出「大概」，
     * 而阈值判错一档的后果是「勉强能用」被写成「可接受」。
     */
    public static String gradeOf(double pct) {
        if (pct <= 10) {
            return VERDICT_GOOD;
        }
        return pct <= 30 ? VERDICT_MARGINAL : VERDICT_BAD;
    }

    /** 定档的短标签：用在句子中间，不带那句长解释 */
    public static String gradeLabel(String verdict) {
        return switch (verdict) {
            case VERDICT_GOOD -> "可接受";
            case VERDICT_MARGINAL -> "有条件接受";
            case VERDICT_BAD -> "不可接受";
            default -> "算不出";
        };
    }

    private static String verdictText(double pct, String verdict) {
        return switch (verdict) {
            case VERDICT_GOOD -> "%GRR " + pct + "%，可接受：这套量具和手法足够分辨出这道特性的合格与不合格";
            case VERDICT_MARGINAL -> "%GRR " + pct + "%，有条件接受：能用，但卡在公差边上那几件判起来会犹豫；"
                    + "客户要求严、或者这道特性是关键特性时，评审要专门说一句";
            default -> "%GRR " + pct + "%，不可接受：读数的波动已经能和零件本身的差异相比，"
                    + "这样的数据判合格与不合格基本靠运气，先改测量系统再谈过程能力";
        };
    }

    /** 和 SPC 判异里的「车间该干什么」同一个路数：先分清是量具的问题还是人的问题 */
    private static String advice(double pctEv, double pctAv, String verdict) {
        if (VERDICT_GOOD.equals(verdict)) {
            return null;
        }
        if (pctEv >= pctAv * 1.5) {
            return "重复性占大头（EV " + pctEv + "%，AV " + pctAv + "%）：同一个人测同一件，反复几次就差这么多。"
                    + "先查量具的分辨力和装夹方式，换人不解决问题";
        }
        if (pctAv >= pctEv * 1.5) {
            return "再现性占大头（AV " + pctAv + "%，EV " + pctEv + "%）：人跟人对不上。"
                    + "先统一定位基准、读数取点、夹紧力，换量具不解决问题";
        }
        return "重复性与再现性各占一半（EV " + pctEv + "%，AV " + pctAv + "%）：两头都要动，"
                + "先统一测量手法，再评估量具是否够用";
    }

    private static double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
