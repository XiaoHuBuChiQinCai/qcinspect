package com.qc.inspect.report;

import com.qc.inspect.spc.CapabilityMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 月报的「过程能力」结论文本。
 *
 * <p>月报前面几段回答「这个月做得怎么样」，这一段回答的是<strong>「下个月会不会更差」</strong>。
 * 合格率是滞后指标：它掉下来的时候，不良已经做出来、已经返工、已经算进成本了。
 * Cpk 是唯一能在出批不良<em>之前</em>就看出来的那个数——所以这一段的价值不在「本月 Cpk 是多少」，
 * 而在「哪几项在往下走」。
 *
 * <h3>为什么刻意不报「全厂平均 Cpk」</h3>
 * <p>不同特性的公差宽度差一个数量级，平均出来的数没有任何物理含义；更糟的是它会被
 * 公差宽、能力高的那几项拉上去，正好盖住真正在出问题的那一项。所以这里只逐项说，
 * 而且测试里锁住了「结论里不许出现平均 Cpk」——免得以后有人觉得少个总览数不好看，补一个上去。
 *
 * <h3>为什么按月算的 Cpk 会和 SPC 页上的不一样</h3>
 * <p>窗口不同：SPC 页默认看 90 天，月报只看一个自然月。样本少了，σ̂ 的抖动就大。
 * 两处用的是同一套算法（{@link CapabilityMath}），但<strong>不是同一个数</strong>，
 * 别拿月报的 Cpk 去和客户对账——要对外报数用 SPC 页按客户要求的区间跑一次。
 */
public final class CapabilityNarrative {

    /** Cpk 比上月掉这么多才算「明显下滑」。0.3 大约相当于 σ 涨了 25%，已经不是抽样噪声能解释的 */
    public static final double SLIP = 0.30;

    private CapabilityNarrative() {
    }

    /**
     * @param items       本月有规格限的特性；{@code n} 不足 {@link CapabilityMath#MIN_SAMPLE}
     *                    的由这里自己分出来，调用方不用先筛
     * @param specChanged 规格限在两个月之间变过、因而没做环比的项数
     */
    public static List<String> build(List<Item> items, int specChanged) {
        List<String> out = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            out.add("本月没有可评过程能力的特性。只有「设了规格上下限、并且录了实测数值」的检验项"
                    + "才算得出 Cpk；只判合格/不合格的项没有均值和标准差，谈不上能力。");
            return out;
        }

        List<Item> usable = new ArrayList<>();
        List<Item> few = new ArrayList<>();
        for (Item it : items) {
            if (it.cpk() == null) {
                // 连单边规格都没有的项算不出 Cpk，按「判不了」处理，别混进分母
                few.add(it);
            } else if (it.n() < CapabilityMath.MIN_SAMPLE) {
                few.add(it);
            } else {
                usable.add(it);
            }
        }

        if (usable.isEmpty()) {
            int most = items.stream().mapToInt(Item::n).max().orElse(0);
            out.add("本月 " + items.size() + " 项特性都设了规格限，但样本最多的也只有 " + most
                    + " 个，不到 " + CapabilityMath.MIN_SAMPLE + " 个。本月不判过程能力——"
                    + "样本少的时候标准差自己就在抖，算出来的 Cpk 是噪声不是结论。");
            return out;
        }

        List<Item> weak = usable.stream().filter(i -> i.cpk() < 1.00).collect(Collectors.toList());
        List<Item> marginal = usable.stream()
                .filter(i -> i.cpk() >= 1.00 && i.cpk() < 1.33).collect(Collectors.toList());
        List<Item> slipping = usable.stream().filter(CapabilityNarrative::isSlipping)
                .collect(Collectors.toList());

        // ---- 1. 概要 ----
        // 只报非零的那几档：写「能力不足 0 项、勉强 0 项」会把一句好消息读成一串体检指标
        StringBuilder head = new StringBuilder();
        head.append("本月 ").append(usable.size()).append(" 个特性够样本可判过程能力，");
        int good = usable.size() - weak.size() - marginal.size();
        if (weak.isEmpty() && marginal.isEmpty()) {
            head.append("全部在 1.33 以上。");
        } else {
            List<String> parts = new ArrayList<>();
            if (!weak.isEmpty()) {
                parts.add("能力不足 " + weak.size() + " 项");
            }
            if (!marginal.isEmpty()) {
                parts.add("勉强 " + marginal.size() + " 项");
            }
            head.append(String.join("、", parts));
            if (good > 0) {
                head.append("，其余 ").append(good).append(" 项在 1.33 以上");
            }
            head.append("。");
        }
        out.add(head.toString());

        // ---- 2. 能力不足：本月就已经在持续出不良的那几项 ----
        if (!weak.isEmpty()) {
            out.add("能力不足（Cpk < 1.00）的是 " + list(weak)
                    + "。按现在的波动，正常生产也会持续出不良——先动工艺、夹具或来料，"
                    + "光靠加严检验是兜不住的，检验只能把不良挑出来，挑不出来的是成本。");
        }

        // ---- 3. 明显下滑：这一段才是月报里别人给不出的东西 ----
        if (!slipping.isEmpty()) {
            out.add("另有 " + slipping.size() + " 项比上月明显下滑：" + changeList(slipping)
                    + "。现在还没变成不良，但照这个势头下个月就会出批不合格——"
                    + "先查刀具磨损、夹具定位和这批来料的波动。");
        }

        // ---- 4. 勉强 ----
        if (!marginal.isEmpty()) {
            out.add("在 1.00~1.33 之间的还有 " + marginal.size() + " 项，属于勉强：稍有漂移就会出批不合格，"
                    + "列为下月的重点盯防对象。");
        }

        // ---- 4. 判不了的 ----
        if (!few.isEmpty()) {
            out.add("另有 " + few.size() + " 项本月样本不足 " + CapabilityMath.MIN_SAMPLE
                    + " 个，本月不判能力。样本太少时 Cpk 的抖动比你要看的变化还大，"
                    + "拿它下结论只会误伤——要评就多抽几件再说。");
        }
        if (specChanged > 0) {
            out.add("有 " + specChanged + " 项的规格限在这两个月之间变过，环比不比："
                    + "两边的分母不是同一道公差，比出来的差值说明不了任何事。");
        }

        return out;
    }

    /** 环比是否算「明显下滑」。任一期样本不足就是不可比，不能拿噪声当趋势 */
    public static boolean isSlipping(Item it) {
        if (it.cpk() == null || it.prevCpk() == null) {
            return false;
        }
        if (it.n() < CapabilityMath.MIN_SAMPLE || it.prevN() < CapabilityMath.MIN_SAMPLE) {
            return false;
        }
        return it.prevCpk() - it.cpk() >= SLIP - 1e-9;
    }

    /** 最多列三项，剩下的说「等N项」，否则一句话里塞八个名字没人读得下去 */
    private static String list(List<Item> items) {
        return items.stream().limit(3)
                .map(i -> i.label() + "（" + num(i.cpk()) + "）")
                .collect(Collectors.joining("、"))
                + (items.size() > 3 ? " 等 " + items.size() + " 项" : "");
    }

    private static String changeList(List<Item> items) {
        return items.stream().limit(3)
                .map(i -> i.label() + " " + num(i.prevCpk()) + "→" + num(i.cpk()))
                .collect(Collectors.joining("、"))
                + (items.size() > 3 ? " 等 " + items.size() + " 项" : "");
    }

    private static String num(Double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 一项特性的月度能力。
     *
     * @param label    给人看的名字，一般是「产品 · 检验项」
     * @param n        本月实测值个数
     * @param cpk      本月 Cpk；算不出（无规格限或波动为 0）时为 null
     * @param prevCpk  上月 Cpk；上月样本不足、规格变过或没数据时一律 null，
     *                 表示「不可比」而不是「上个月很差」
     * @param prevN    上月实测值个数，用来判断环比是否可信
     */
    public record Item(String label, int n, Double cpk, Double prevCpk, int prevN) {
    }
}
