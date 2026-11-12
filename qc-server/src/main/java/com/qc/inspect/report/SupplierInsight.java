package com.qc.inspect.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商画像的判断与结论。
 *
 * <p>主数据列表上只给「件级合格率 94.2%」是不解决问题的：采购拿着这个数去找供应商，
 * 对方一句「那 5.8% 是哪几批、坏在哪」就把人问住了。而更要紧的是——
 * <b>合格率相同的两家，问题性质可能完全相反</b>：
 *
 * <ul>
 *   <li>12 批里 11 批零不良、1 批废掉 40% → 偶发。要查那一批的料、运输、换模记录，
 *       谈的是「这一批怎么了」。这时候换供应商是最贵的错</li>
 *   <li>12 批每批都坏 5% 左右 → 系统性。这不是运气，是这家厂现在的能力就在这儿，
 *       谈的是「工艺要怎么改」，改不动就得考虑分单</li>
 * </ul>
 *
 * <p>两者都值得给出平均合格率 95%，而该做的事一件都不沾边。
 *
 * <p>所以这里先算「批次分布」（零不良几批、有不良几批、最差那批多少），
 * 再决定说哪种话。所有判断集中在这一处，前端不再判第二次。
 */
public final class SupplierInsight {

    private SupplierInsight() {
    }

    /** 样本少到这个数以下就不下结论：三批里坏两批，可能只是运气 */
    public static final int MIN_LOTS_FOR_VERDICT = 3;

    /** 有不良的批次占到这个比例以下，算「偶发」 */
    private static final double SPORADIC_MAX = 1.0 / 3;
    /** 有不良的批次占到这个比例以上，算「每批都有」 */
    private static final double SYSTEMATIC_MIN = 2.0 / 3;

    /**
     * @param batches        批次总数
     * @param lots           检验单数（一个批次可能检多次：复检、分次到货）
     * @param pieceTotal     受检件数
     * @param piecePass      合格件数
     * @param perfectLots    零不良的检验单数
     * @param badLots        有不良的检验单数
     * @param worstBatchRate 单张检验单里最低的合格率（最差的那一批）
     */
    public record Profile(long batches, long lots, long pieceTotal, long piecePass,
                          long perfectLots, long badLots, Double worstBatchRate) {

        /** 件级合格率。件数为 0 时返回 null——「一件没检」不是「全合格」 */
        public Double yieldRate() {
            if (pieceTotal <= 0) {
                return null;
            }
            return (double) piecePass / pieceTotal;
        }

        /** 有不良的检验单占比。样本为 0 时 null */
        public Double badShare() {
            if (lots <= 0) {
                return null;
            }
            return (double) badLots / lots;
        }

        public boolean isEmpty() {
            return lots <= 0;
        }
    }

    /** 问题性质，页面上的分档标签就取这个 */
    public enum Kind {
        /** 一批都还没检 */
        NO_DATA("无检验"),
        /** 样本不够，不下结论 */
        TOO_FEW("样本不足"),
        /** 零不良 */
        CLEAN("无不良"),
        /** 问题集中在少数批次 */
        SPORADIC("偶发"),
        /** 每批都有 */
        SYSTEMATIC("系统性"),
        /** 界于两者之间 */
        UNSTABLE("不稳定");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 判问题性质。
     *
     * <p>顺序有讲究：先排除「没数据」和「样本不足」，再判「零不良」，
     * 最后才分偶发 / 不稳定 / 系统性。反过来的话，一批坏一件也会被判成「系统性」。
     */
    public static Kind kind(Profile p) {
        if (p == null || p.isEmpty()) {
            return Kind.NO_DATA;
        }
        if (p.lots() < MIN_LOTS_FOR_VERDICT) {
            return Kind.TOO_FEW;
        }
        if (p.badLots() == 0) {
            return Kind.CLEAN;
        }
        Double share = p.badShare();
        if (share == null) {
            return Kind.NO_DATA;
        }
        if (share <= SPORADIC_MAX) {
            return Kind.SPORADIC;
        }
        if (share >= SYSTEMATIC_MIN) {
            return Kind.SYSTEMATIC;
        }
        return Kind.UNSTABLE;
    }

    /**
     * 结论句。每句都要指向动作——「这个供应商质量不稳定」是一句没有用的话，
     * 采购拿着它什么也做不了。
     *
     * @param topDefectName 该供应商不良件数最多的那项，没有传 null
     * @param topShare      那一项占该供应商全部不良件数的比例
     * @param trendNote     按月趋势的一句话，由调用方拼好（那边才知道月份口径）；没有传 null
     */
    public static List<String> build(Profile p, String topDefectName, Double topShare, String trendNote) {
        List<String> out = new ArrayList<>();
        if (p == null) {
            return out;
        }

        if (p.isEmpty()) {
            out.add("这个时间窗内没有这家供应商的检验记录。"
                    + "要么还没到货，要么批次建立时没挂档——没挂档的批次不会进这里，"
                    + "统计上等于把这家厂的问题藏了起来。");
            return out;
        }

        Kind kind = kind(p);

        // ---------- 1. 总量 ----------
        StringBuilder head = new StringBuilder();
        head.append("近 ").append(p.lots()).append(" 张检验单、")
                .append(p.batches()).append(" 个批次、受检 ").append(p.pieceTotal()).append(" 件");
        Double yield = p.yieldRate();
        if (yield != null) {
            head.append("，件级合格率 ").append(pct(yield));
        }
        head.append("。");
        out.add(head.toString());

        // ---------- 2. 问题性质 ----------
        switch (kind) {
            case TOO_FEW -> out.add("只有 " + p.lots() + " 张检验单，还不足以下判断——"
                    + "三批里坏两批，很可能只是运气。先攒够 " + MIN_LOTS_FOR_VERDICT
                    + " 张再看，现在就找供应商谈话容易谈错方向。");
            case CLEAN -> out.add("这段时间没有出现任何不良。"
                    + "注意「没检出来」和「确实没有」是两件事：抽检比例低的时候，"
                    + "零不良只说明这批货没被抽到问题。");
            case SPORADIC -> {
                StringBuilder s = new StringBuilder();
                s.append(p.lots()).append(" 张里只有 ").append(p.badLots()).append(" 张出现不良，其余 ")
                        .append(p.perfectLots()).append(" 张零不良。");
                if (p.worstBatchRate() != null && p.worstBatchRate() < 0.9) {
                    s.append("最差的那一批合格率只有 ").append(pct(p.worstBatchRate())).append("。");
                }
                s.append("这是偶发，不是这家厂的常态。先查那几批的来料、运输和换模记录，"
                        + "谈的是「这一批怎么了」——这时候换供应商是最贵的一种错。");
                out.add(s.toString());
            }
            case SYSTEMATIC -> {
                StringBuilder s = new StringBuilder();
                s.append(p.lots()).append(" 张里 ").append(p.badLots()).append(" 张都有不良。");
                if (p.perfectLots() == 0) {
                    s.append("一张零不良的都没有——");
                }
                s.append("这不是运气问题，是这家厂目前的能力就在这个水平。"
                        + "谈的应该是「工艺要怎么改、改到什么程度」，改不动就得考虑分单。");
                out.add(s.toString());
            }
            case UNSTABLE -> {
                StringBuilder s = new StringBuilder();
                s.append(p.lots()).append(" 张里 ").append(p.badLots()).append(" 张有不良、")
                        .append(p.perfectLots()).append(" 张零不良，好坏混着来。");
                if (p.worstBatchRate() != null) {
                    s.append("最差的一批合格率 ").append(pct(p.worstBatchRate())).append("，");
                }
                s.append("说明过程没被控制住：同一套要求下时好时坏，比一直差更难处理。"
                        + "要问的是「好的那几批和差的那几批，做的有什么不一样」。");
                out.add(s.toString());
            }
            default -> {
                // NO_DATA 已在上面的 isEmpty 里返回，这里不会到
            }
        }

        // ---------- 3. 不良集中在哪 ----------
        if (topDefectName != null && !topDefectName.isBlank() && topShare != null && topShare > 0) {
            StringBuilder d = new StringBuilder("不良主要集中在「").append(topDefectName).append("」，占 ")
                    .append(pct(topShare));
            // 一半以上：谈一个项就能吃掉大半问题，这比谈「整体质量」有用得多
            if (topShare >= 0.5) {
                d.append("。过半了——先只谈这一项，别一次提十条要求，提十条等于没提。");
            } else {
                d.append("。占比没到一半，说明问题分散，逐项整改会拖很久，"
                        + "先看这几项有没有共同的工序原因。");
            }
            out.add(d.toString());
        }

        // ---------- 4. 趋势 ----------
        if (trendNote != null && !trendNote.isBlank()) {
            out.add(trendNote);
        }

        return out;
    }

    /** 比例 → 百分数；null 显示「-」，不显示 0.0% */
    private static String pct(Double rate) {
        if (rate == null) {
            return "-";
        }
        return String.format("%.1f%%", rate * 100);
    }

    // ------------------------------------------------------------------ 趋势

    /** 一个月的合格件数与受检件数 */
    public record MonthPoint(String month, long pieceTotal, long piecePass) {
    }

    /** 分前后两半比合格率时，每半至少要有这么多件，否则「趋势」只是两三批货的抖动 */
    public static final int MIN_PIECES_FOR_TREND = 30;
    /** 差不到 2 个百分点不算趋势：抽检本来就有这个量级的波动 */
    private static final double TREND_PP = 2.0;
    /** 少于 4 个月分不出「前后两半」，3 个月是一边 1 个月一边 2 个月，谈不上对比 */
    private static final int MIN_MONTHS_FOR_TREND = 4;

    /**
     * 按月看是变好还是变差。
     *
     * <p>不拿「首月比末月」：首月常常只有一批货，末月也可能只检了一张单，
     * 单批的波动会让趋势完全反过来。改成**按件数加权的前后两段对比**——
     * 前一半的合格件数 ÷ 前一半的受检件数，和后一半比。
     *
     * <p>返回 {@code null} 表示「没有可说的」：月份不够、或者某一段件数太少。
     * 宁可不说，也不要拿两批货编一条趋势——那种话说出去会被对方一句话顶回来。
     */
    public static String trendNote(List<MonthPoint> months) {
        if (months == null || months.size() < MIN_MONTHS_FOR_TREND) {
            return null;
        }
        int mid = months.size() / 2;
        long aTotal = 0, aPass = 0, bTotal = 0, bPass = 0;
        for (int i = 0; i < mid; i++) {
            aTotal += months.get(i).pieceTotal();
            aPass += months.get(i).piecePass();
        }
        for (int i = mid; i < months.size(); i++) {
            bTotal += months.get(i).pieceTotal();
            bPass += months.get(i).piecePass();
        }
        if (aTotal < MIN_PIECES_FOR_TREND || bTotal < MIN_PIECES_FOR_TREND) {
            return null;
        }
        double before = (double) aPass / aTotal;
        double after = (double) bPass / bTotal;
        double pp = round1((after - before) * 100);

        if (Math.abs(pp) < TREND_PP) {
            return "前后两段的合格率差 " + ppText(Math.abs(pp)) + " 个百分点，看不出明显趋势——"
                    + "这家厂这段时间一直就这个水平。";
        }
        if (pp < 0) {
            return "合格率从 " + pct(before) + " 掉到 " + pct(after) + "（降 "
                    + ppText(Math.abs(pp)) + " 个百分点）。这个幅度超出抽检波动，"
                    + "不是运气问题——下一次订货前得问清楚他们那边改了什么。";
        }
        return "合格率从 " + pct(before) + " 升到 " + pct(after) + "（升 " + ppText(pp) + " 个百分点）。"
                + "有改善，但要看是靠筛出来的还是真把过程改了："
                + "如果是前者，一旦放松全检就会掉回去。";
    }

    /** 差值的显示：保留一位，整数不带 .0（「2.0 个百分点」看着像算了什么精密的数） */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String ppText(double v) {
        double r = round1(v);
        return Math.abs(r - Math.rint(r)) < 0.05
                ? String.valueOf((long) Math.rint(r))
                : String.format("%.1f", r);
    }
}
