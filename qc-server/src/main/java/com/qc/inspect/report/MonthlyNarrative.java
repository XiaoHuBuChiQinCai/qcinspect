package com.qc.inspect.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 月报的结论文本。
 *
 * <p>一张表堆满数字，看的人还是要自己算一遍才知道「这个月到底好不好」。
 * 这里把数字翻成几句人话，顺序按管理评审会问问题的顺序排：
 * 总量 → 合格率及环比 → 主要不良 → 客诉 → 计量器具 → 返工。
 *
 * <p>为什么单独成类：结论里最容易出的是「假警报」——没有数据时说合格率 0%、
 * 上月没数据时说上升 100 个百分点。这些在页面上看着像真的，必须能单测。
 */
public final class MonthlyNarrative {

    private MonthlyNarrative() {
    }

    public static List<String> build(MonthlyReport cur, MonthlyReport prev, String label,
                                     String topDefectText,
                                     long complaintOpened, long complaintHigh,
                                     long complaintClosed, long complaintOpen,
                                     long gaugesOverdue, long gaugesSuspended) {
        List<String> out = new ArrayList<>();

        if (cur == null || cur.isEmpty()) {
            out.add(label + "没有任何已提交的检验单。本月结论为空——不是全部合格，是没有检验。");
            return out;
        }

        // ---------- 1. 总量与合格率 ----------
        StringBuilder head = new StringBuilder();
        head.append(label).append("共完成 ").append(cur.orders()).append(" 张检验单、受检 ")
                .append(cur.pieceTotal()).append(" 件，件级合格率 ")
                .append(pct(cur.yieldRate()));

        if (prev == null || prev.isEmpty()) {
            head.append("。上月没有可比的检验数据，本期不计算环比。");
        } else {
            Double pp = cur.yieldDeltaPp(prev);
            if (pp == null) {
                head.append("。上月无可比数据，本期不计算环比。");
            } else if (pp == 0) {
                head.append("，与上月（").append(pct(prev.yieldRate())).append("）持平。");
            } else {
                head.append("，较上月（").append(pct(prev.yieldRate())).append("）")
                        .append(pp > 0 ? "上升 " : "下降 ")
                        .append(Math.abs(pp)).append(" 个百分点。");
            }
        }
        out.add(head.toString());

        // ---------- 2. 不良 ----------
        Long ppm = cur.ppm();
        if (cur.pieceFail() > 0) {
            StringBuilder d = new StringBuilder();
            d.append("不良 ").append(cur.pieceFail()).append(" 件");
            if (ppm != null) {
                d.append("（").append(ppm).append(" PPM）");
            }
            if (topDefectText != null && !topDefectText.isBlank()) {
                d.append("，主要集中在这几项：").append(topDefectText);
                d.append("。优先从这几项下手，改一项就能吃掉大部分不良。");
            } else {
                d.append("。");
            }
            out.add(d.toString());
        } else {
            out.add("本月没有不良记录，所有受检件均合格。");
        }

        // ---------- 3. 客诉 ----------
        if (complaintOpened == 0 && complaintClosed == 0 && complaintOpen == 0) {
            out.add("本月无客户投诉，也无历史客诉积压。");
        } else {
            StringBuilder c = new StringBuilder("客诉");
            c.append("新开 ").append(complaintOpened).append(" 起");
            if (complaintHigh > 0) {
                c.append("（其中高优先级 ").append(complaintHigh).append(" 起）");
            }
            c.append("，本月关闭 ").append(complaintClosed).append(" 起");
            c.append("；截止目前仍有 ").append(complaintOpen).append(" 起未关闭");
            if (complaintOpen > 0) {
                c.append("，未关闭的客诉会拦住对应批次出货，需尽快出根因和对策");
            }
            c.append("。");
            out.add(c.toString());
        }

        // ---------- 4. 计量器具 ----------
        if (gaugesOverdue > 0 || gaugesSuspended > 0) {
            StringBuilder g = new StringBuilder("计量器具：");
            if (gaugesOverdue > 0) {
                g.append("有 ").append(gaugesOverdue).append(" 把已过校准有效期仍在台账中");
            }
            if (gaugesOverdue > 0 && gaugesSuspended > 0) {
                g.append("，");
            }
            if (gaugesSuspended > 0) {
                g.append("有 ").append(gaugesSuspended).append(" 把校准不合格处于停用状态");
            }
            g.append("。超期量具不得用于检验，提交时系统会拦下，但台账本身该在本月内清掉。");
            out.add(g.toString());
        }

        // ---------- 5. 返工 ----------
        if (cur.recheckOrders() > 0) {
            Double rate = cur.recheckRate();
            out.add("本月有 " + cur.recheckOrders() + " 张返工复检单，占全部检验单 "
                    + pct(rate) + "。返工复检按逐件全检、Ac=0 判，复检不过的件只能报废或让步。");
        }

        return out;
    }

    /** 比例 → 百分数字符串；null（无数据）显示「-」，不要显示 0.0% */
    private static String pct(Double rate) {
        if (rate == null) {
            return "-";
        }
        return String.format("%.1f%%", rate * 100);
    }
}
