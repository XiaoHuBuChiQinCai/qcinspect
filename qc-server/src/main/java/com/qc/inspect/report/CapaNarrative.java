package com.qc.inspect.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 月报里「纠正措施」那一段的结论文本。
 *
 * <p>为什么不并进 {@link MonthlyNarrative}：那边讲的是「这个月做得怎么样」（检验、不良、客诉），
 * 这边讲的是「做错了之后改没改」。两件事的数据来源、问法、该做的动作都不一样，
 * 塞进一个方法会变成十几个参数的签名，也没法分别测。
 *
 * <p><b>这里永远不出现「验证通过率」。</b>一个刻意不给的数：表里只存最后一次验证结果，
 * 退回后重新验证通过就覆盖了，算出来的值衡量的其实是「谁更容易被劝退」。
 * 更要紧的是，一旦这个数上了月报，验证人就开始不判无效了——而判无效恰恰是这套机制
 * 唯一能防住「表上全是已验证有效、问题照旧发生」的东西。
 *
 * <p>每一句都尽量指向动作，不指向评价。「本月立了 3 条」是一句记录，
 * 「本月有 47 件不良、一条措施都没立」才是管理评审上会被追问的那句。
 */
public final class CapaNarrative {

    private CapaNarrative() {
    }

    /**
     * 一屏数字。用 record 而不是十几个参数：调用点已经够长，
     * 再加一个数就得回去数位置，早晚传错。
     *
     * @param opened        本月新立
     * @param closed        本月关闭
     * @param rejected      本月判无效退回（按 verified_at 落窗口）
     * @param cancelled     本月撤销
     * @param openNow       当前还开着（快照）
     * @param overdue       当前已超期（快照）
     * @param maxDaysOpen   当前挂着最久的一条立了几天（快照）
     * @param avgCloseDays  本月关闭的平均用了几天
     */
    public record Summary(long opened, long closed, long rejected, long cancelled,
                          long openNow, long overdue, int maxDaysOpen, Double avgCloseDays) {
    }

    /**
     * @param defectQty     本月不良件数。用来判断「没立措施」是因为没出问题，还是因为没人管
     * @param slowestTitle  本月关闭里拖得最久的那条的标题，没有就传 null
     * @param slowestDays   同上，拖了几天
     */
    public static List<String> build(Summary s, long defectQty,
                                     String slowestTitle, int slowestDays) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }

        boolean nothingHappened = s.opened() == 0 && s.closed() == 0 && s.openNow() == 0;

        // ---------- 1. 一条都没有 ----------
        if (nothingHappened) {
            if (defectQty > 0) {
                // 这是整段最该被看见的一句。不良被返工/报废「处理」掉了，问题本身没被改，
                // 下个月同样的地方还会坏——而那时所有人都会觉得「又是这个毛病」
                out.add("本月有 " + defectQty + " 件不良，但没有立项任何纠正措施。"
                        + "不良被处置掉了，问题本身没有被人改过——下个月同样的地方还会坏，"
                        + "而那时大家只会说「怎么又是这个毛病」。");
            } else {
                out.add("本月没有立项纠正措施，也没有不良记录。");
            }
            return out;
        }

        // ---------- 2. 存量与流量 ----------
        StringBuilder head = new StringBuilder("纠正措施：");
        if (s.opened() > 0) {
            head.append("本月新立 ").append(s.opened()).append(" 条");
        } else {
            head.append("本月没有新立措施");
        }
        if (s.closed() > 0) {
            head.append("，关闭 ").append(s.closed()).append(" 条");
        } else {
            head.append("，本月没有关闭任何一条");
        }
        if (s.openNow() > 0) {
            head.append("，目前仍有 ").append(s.openNow()).append(" 条在改");
        } else {
            head.append("，手上没有未完成的措施");
        }
        head.append("。");

        // 「关了 0 条」单说一句，否则它埋在上面的小句里，看的人以为是「没什么事」
        if (s.closed() == 0 && s.openNow() > 0) {
            head.append("注意这个月一条都没关上——措施停在「在改」的状态太久，"
                    + "通常不是还在改，是没人再问起。");
        }
        out.add(head.toString());

        // ---------- 3. 判无效 ----------
        if (s.rejected() > 0) {
            out.add("其中 " + s.rejected() + " 条验证判为无效、退回重改。"
                    + "这不是坏消息：它说明验证的人在拿数据判，而不是看见「已完成」就点通过。"
                    + "这几条下个月要回头看，改完还不管用就得换方向，别再照着原来那套加力度。");
        }

        // ---------- 4. 超期 ----------
        if (s.overdue() > 0) {
            StringBuilder o = new StringBuilder("有 ").append(s.overdue())
                    .append(" 条已过要求完成日仍在改");
            if (s.maxDaysOpen() > 0) {
                o.append("，其中立得最久的一条已经挂了 ").append(s.maxDaysOpen()).append(" 天");
            }
            o.append("。措施超期通常不是没时间，是没人再催——");
            o.append("挂着超过两个月的那条，基本可以当成「已经放弃了但没写撤销理由」。");
            out.add(o.toString());
        }

        // ---------- 5. 关闭用时 ----------
        if (s.avgCloseDays() != null && s.closed() > 0) {
            StringBuilder d = new StringBuilder("本月关闭的措施平均用了 ")
                    .append(fmtDays(s.avgCloseDays())).append(" 天");
            if (slowestTitle != null && !slowestTitle.isBlank() && slowestDays > 0) {
                d.append("，最慢的一条「").append(slowestTitle).append("」拖了 ")
                        .append(slowestDays).append(" 天");
            }
            d.append("。");
            out.add(d.toString());
        }

        // ---------- 6. 撤销 ----------
        if (s.cancelled() > 0) {
            out.add("本月撤销 " + s.cancelled() + " 条。撤销本身没问题——"
                    + "把不该立的撤掉，比让它挂在那里假装在改要好。");
        }

        return out;
    }

    /** 1.0 天说成「1 天」，3.5 天保留一位：平均值的精度假到小数点后两位反而像算错了 */
    private static String fmtDays(double d) {
        if (Math.abs(d - Math.rint(d)) < 0.05) {
            return String.valueOf((long) Math.rint(d));
        }
        return String.format("%.1f", d);
    }
}
