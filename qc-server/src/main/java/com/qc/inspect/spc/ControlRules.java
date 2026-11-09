package com.qc.inspect.spc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoublePredicate;

/**
 * 控制图的判异准则（GB/T 4091 / 常规八准则）。
 *
 * <p>为什么需要这个：控制图本来是用来「判读」的，不是用来「看」的。画了 UCL/LCL 却没有判异，
 * 一条连续 9 点偏在中心线上侧的曲线，看起来和正常曲线一模一样——只有超出控制限才会被注意到，
 * 而真到那一步，批量不良已经做出来了。
 *
 * <p>纯计算，不碰数据库也不碰格式，方便单测把每条准则的窗口长度和边界钉死。
 */
public final class ControlRules {

    private ControlRules() {
    }

    /**
     * 一条被触发的准则。
     *
     * @param fromSeq / toSeq 最近一次触发所在的点序号（从 1 起），页面用它标红
     * @param hits            整张图上触发了几次；页面上只报最近一次的位置，但次数说明「是老毛病还是偶尔一次」
     */
    public record Alarm(String code, String level, String title, String text,
                        int fromSeq, int toSeq, int hits) {
    }

    /**
     * @param verdict     STABLE / WARNING / OUT_OF_CONTROL / UNKNOWN
     * @param verdictText 一句话结论，直接给页面显示
     */
    public record Report(String verdict, String verdictText, List<Alarm> alarms) {
    }

    public static final String OUT_OF_CONTROL = "OUT_OF_CONTROL";
    public static final String WARNING = "WARNING";
    public static final String STABLE = "STABLE";
    public static final String UNKNOWN = "UNKNOWN";

    /** 边界值的浮点容差：正好落在 1σ / 2σ / 3σ 上的点，既不算「在外」也不算「在内」 */
    private static final double EPS = 1e-9;

    /**
     * 判读整张单值图。
     *
     * @param values 按时间顺序的实测值
     * @param center 中心线（均值）
     * @param sigma  组内 σ̂（= MR̄ / 1.128）。必须与画控制限用的是同一个，否则点会「在限外但判无异」
     */
    public static Report check(List<Double> values, double center, double sigma) {
        List<Alarm> alarms = new ArrayList<>();
        if (values == null || values.size() < 2) {
            return new Report(STABLE, "点太少，判不了趋势（至少要 2 个点）", alarms);
        }
        // σ̂ 为 0 说明所有测量值完全一样：1σ/2σ/3σ 全degenerate 成一个点，任何比较都没意义。
        // 这种情况本身就可疑（量具分辨力不足或数据是补填的），但不该由判异规则来报
        if (!(sigma > 0)) {
            return new Report(UNKNOWN, "σ̂ 为 0：所有测量值完全相同，判异规则无从计算", alarms);
        }

        beyond3(values, center, sigma, alarms);
        sameSideRun(values, center, alarms);
        trend(values, alarms);
        alternating(values, alarms);
        twoOfThree(values, center, sigma, alarms);
        fourOfFive(values, center, sigma, alarms);
        huggingCenter(values, center, sigma, alarms);
        outsideOneSigma(values, center, sigma, alarms);

        String verdict;
        String verdictText;
        if (alarms.isEmpty()) {
            verdict = STABLE;
            verdictText = "未见异常：点都在控制限内，也没有形成连续偏移或趋势";
        } else if (alarms.stream().anyMatch(a -> "R1".equals(a.code()))) {
            verdict = OUT_OF_CONTROL;
            verdictText = "过程已失控：有实测值超出控制限，先把这一段的批次挑出来，再查人机料法环";
        } else {
            verdict = WARNING;
            verdictText = "过程出现异常倾向：点还没超出控制限，但排列已经不像随机波动了";
        }
        return new Report(verdict, verdictText, alarms);
    }

    /** 准则 1：1 点落在 ±3σ 之外 */
    private static void beyond3(List<Double> v, double c, double s, List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        for (int i = 0; i < v.size(); i++) {
            if (Math.abs(v.get(i) - c) > 3 * s + EPS) {
                hits++;
                from = i + 1;
                to = i + 1;
            }
        }
        if (hits > 0) {
            out.add(new Alarm("R1", "CRITICAL", "单点出界",
                    "第 " + from + " 个点超出 3σ 控制限。这不是「差一点点」，是过程已经不在统计掌控内，"
                            + "对应的那几张单要挑出来单独确认。共出现 " + hits + " 次",
                    from, to, hits));
        }
    }

    /** 准则 2：连续 9 点落在中心线同一侧 */
    private static void sameSideRun(List<Double> v, double c, List<Alarm> out) {
        latestRun(v, 9, x -> x > c + EPS, "R2", "同侧偏移",
                "连续 9 个点都在中心线上方，均值已经整体上移。再看单点有没有超限没有意义——"
                        + "这时候查的是「什么时候开始偏的」", out);
        latestRun(v, 9, x -> x < c - EPS, "R2", "同侧偏移",
                "连续 9 个点都在中心线下方，均值已经整体下移。再看单点有没有超限没有意义——"
                        + "这时候查的是「什么时候开始偏的」", out);
    }

    /** 准则 3：连续 6 点递增或递减 */
    private static void trend(List<Double> v, List<Alarm> out) {
        risingOrFalling(v, true, out);
        risingOrFalling(v, false, out);
    }

    private static void risingOrFalling(List<Double> v, boolean rising, List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        int run = 1;
        for (int i = 1; i < v.size(); i++) {
            boolean step = rising
                    ? v.get(i) > v.get(i - 1) + EPS
                    : v.get(i) < v.get(i - 1) - EPS;
            run = step ? run + 1 : 1;
            if (run >= 6) {
                hits++;
                to = i + 1;
                from = i - 4;   // 6 个点的窗口：i-5..i（0 基），首点序号 i-4
            }
        }
        if (hits > 0) {
            String dir = rising ? "上升" : "下降";
            out.add(new Alarm("R3", "WARN", "持续" + dir,
                    "第 " + from + "~" + to + " 个点连续" + dir + "。多为刀具磨损、量具漂移或原料批次切换，"
                            + "趋势不会自己回头，越早调越省料",
                    from, to, hits));
        }
    }

    /** 准则 4：连续 14 点上下交替 */
    private static void alternating(List<Double> v, List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        int run = 1;
        for (int i = 1; i < v.size(); i++) {
            boolean moved = Math.abs(v.get(i) - v.get(i - 1)) > EPS;
            run = moved ? run + 1 : 1;
            if (run >= 14) {
                hits++;
                to = i + 1;
                from = i - 12;  // 14 个点的窗口：i-13..i（0 基），首点序号 i-12
            }
        }
        if (hits > 0) {
            out.add(new Alarm("R4", "WARN", "上下交替",
                    "第 " + from + "~" + to + " 个点一上一下交替出现。两台设备、两个班组或两批原料混着投时常见——"
                            + "数据本身没错，是输入混了",
                    from, to, hits));
        }
    }

    /** 准则 5：连续 3 点中 2 点落在 ±2σ 之外（同侧） */
    private static void twoOfThree(List<Double> v, double c, double s, List<Alarm> out) {
        beyondOfWindow(v, 3, 2, 2.0, c, s, "R5", "接近控制限",
                "过程已经走到控制限边上，下一个点很可能就出去了", out);
    }

    /** 准则 6：连续 5 点中 4 点落在 ±1σ 之外（同侧） */
    private static void fourOfFive(List<Double> v, double c, double s, List<Alarm> out) {
        beyondOfWindow(v, 5, 4, 1.0, c, s, "R6", "外侧聚集",
                "这不是抖动，是被推着走：查最近有没有换料、换模、换人", out);
    }

    /** 准则 7：连续 15 点全在 ±1σ 之内 */
    private static void huggingCenter(List<Double> v, double c, double s, List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        for (int i = 0; i + 15 <= v.size(); i++) {
            boolean all = true;
            for (int k = i; k < i + 15; k++) {
                if (Math.abs(v.get(k) - c) > s - EPS) {
                    all = false;
                    break;
                }
            }
            if (all) {
                hits++;
                from = i + 1;
                to = i + 15;
            }
        }
        if (hits > 0) {
            out.add(new Alarm("R7", "WARN", "波动过小",
                    "第 " + from + "~" + to + " 个点全挤在 1σ 以内。波动小得不像真的："
                            + "先查量具分辨力够不够，再查这批数据是不是照上次数补填的",
                    from, to, hits));
        }
    }

    /** 准则 8：连续 8 点全在 ±1σ 之外（两侧都算） */
    private static void outsideOneSigma(List<Double> v, double c, double s, List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        for (int i = 0; i + 8 <= v.size(); i++) {
            boolean all = true;
            for (int k = i; k < i + 8; k++) {
                if (Math.abs(v.get(k) - c) < s + EPS) {
                    all = false;
                    break;
                }
            }
            if (all) {
                hits++;
                from = i + 1;
                to = i + 8;
            }
        }
        if (hits > 0) {
            out.add(new Alarm("R8", "WARN", "双侧远离",
                    "第 " + from + "~" + to + " 个点全在 1σ 之外，两侧都有。常见于两套数据混在一张图上——"
                            + "两台设备、两个模腔或两种工艺参数",
                    from, to, hits));
        }
    }

    /** 「连续 len 个点都满足条件」。只报最近一次的位置，因为页面关心的是「现在怎么样」 */
    private static void latestRun(List<Double> v, int len, DoublePredicate cond,
                                  String code, String title, String text, List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        int run = 0;
        for (int i = 0; i < v.size(); i++) {
            run = cond.test(v.get(i)) ? run + 1 : 0;
            if (run >= len) {
                hits++;
                to = i + 1;
                from = i - len + 2;
            }
        }
        if (hits > 0) {
            out.add(new Alarm(code, "WARN", title, text + "。共出现 " + hits + " 次", from, to, hits));
        }
    }

    /** 「连续 window 个点里至少有 need 个落在 nσ 之外，且在同一侧」 */
    private static void beyondOfWindow(List<Double> v, int window, int need, double nSigma,
                                       double c, double s, String code, String title, String tail,
                                       List<Alarm> out) {
        int hits = 0;
        int from = -1;
        int to = -1;
        boolean bothSides = false;
        boolean upSeen = false;
        boolean downSeen = false;
        for (int i = 0; i + window <= v.size(); i++) {
            int up = 0;
            int down = 0;
            for (int k = i; k < i + window; k++) {
                double d = v.get(k) - c;
                if (d > nSigma * s + EPS) {
                    up++;
                } else if (d < -nSigma * s - EPS) {
                    down++;
                }
            }
            // 上下两侧分开数：2 个在 +2σ 外，和 1 个 +2σ、1 个 -2σ，是两件不同的事
            if (up >= need) {
                hits++;
                from = i + 1;
                to = i + window;
                upSeen = true;
            }
            if (down >= need) {
                hits++;
                from = i + 1;
                to = i + window;
                downSeen = true;
            }
        }
        if (hits > 0) {
            bothSides = upSeen && downSeen;
            String zone = String.format("%.0fσ", nSigma);
            out.add(new Alarm(code, "WARN", title,
                    "第 " + from + "~" + to + " 个点里，有 " + need + " 个落在 " + zone + " 之外且同一侧"
                            + (bothSides ? "（上下两侧都出现过）" : "") + "。" + tail
                            + "。共出现 " + hits + " 次",
                    from, to, hits));
        }
    }
}
