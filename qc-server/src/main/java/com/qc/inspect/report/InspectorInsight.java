package com.qc.inspect.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 检验员维度的记录可信度分析。
 *
 * <p><b>这一页不是绩效排名。</b>按不良率给人排序，结果是所有人都学会「少判 NG」，
 * 数据立刻变得更好看、也立刻变得没用。这里回答的是另一个问题：
 * <b>哪些记录可能没真正做过</b>，需要主管去抽一单复核。
 *
 * <p>信号只有三类，都只指向「值得看一眼」，不指向「这个人有问题」：
 * <ul>
 *   <li><b>零不良</b>：同类单据里别人判出过不良，而这个人十几单一处不良都没有。
 *       可能是他检的产品确实好，也可能是没测直接填合格——抽查一单就能分清。</li>
 *   <li><b>录入过快</b>：每行平均不到 2 秒。40 行 90 秒提交完，物理上不可能真测。
 *       只对「过快」告警；「慢」不告警，因为现场常常是早上开单、下班前补录，
 *       时长天然很长，快慢本身不是证据。</li>
 *   <li><b>不良率显著偏离同组</b>：低到同组的三分之一以下，或高到两倍以上。
 *       双向都报——低可能是放水，高可能是手法或量具差异，也可能是判得过细
 *       （把毛刺都算 NG 的人，看起来就像个「高产出的问题制造者」）。</li>
 * </ul>
 *
 * <p>阈值都写成常量并有单测锁定：这类规则最怕「悄悄放宽到没有意义」。
 */
public final class InspectorInsight {

    /** 判定「零不良可疑」的最小单量：3 单零不良不说明任何事，10 单才有统计意味 */
    static final int ZERO_DEFECT_MIN_ORDERS = 10;
    /** 判定「录入过快」的最小行量：小单本来就可以很快，行数少时秒数没有意义 */
    static final int RUSHED_MIN_ROWS = 30;
    /** 每行平均秒数低于此值视为过快 */
    static final double RUSHED_SEC_PER_ROW = 2.0;
    /** 做组间对比的最小单量：单子太少时不良率是噪声 */
    static final int COMPARE_MIN_ORDERS = 5;
    /** 低于同组此倍数 → 偏低的提示 */
    static final double LOW_RATIO = 0.34;
    /** 高于同组此倍数 → 偏高的提示（且要有一定不良件数，避免 1/3 与 2/3 的偶然） */
    static final double HIGH_RATIO = 2.0;
    static final int HIGH_MIN_FAIL_PIECES = 3;

    /** 记录可信度有问题（零不良 / 录入过快） */
    public static final String LEVEL_SUSPECT = "SUSPECT";
    /** 可关注：不良率明显偏离同组 */
    public static final String LEVEL_WATCH = "WATCH";
    public static final String LEVEL_OK = "OK";

    private InspectorInsight() {
    }

    /** 一行原始聚合，字段含义与 SQL 别名一一对应 */
    public record Raw(String inspector, long orders, long pieceTotal, long piecePass, long pieceFail,
                      long ngCount, long criticalNg, long passOrders, long recheckOrders,
                      long rowTotal, long secTotal) {

        static Raw empty() {
            return new Raw(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Raw plus(Raw o) {
            return new Raw(inspector, orders + o.orders, pieceTotal + o.pieceTotal,
                    piecePass + o.piecePass, pieceFail + o.pieceFail, ngCount + o.ngCount,
                    criticalNg + o.criticalNg, passOrders + o.passOrders,
                    recheckOrders + o.recheckOrders, rowTotal + o.rowTotal, secTotal + o.secTotal);
        }
    }

    /** 一条提示，带它自己的级别。级别跟着信号走，而不是跟着人走 */
    private record Flag(String level, String text) {
    }

    /**
     * @param raw             原始聚合
     * @param ngRate          件级不良率，分母 0 时为 null（不是 0——「没检」和「全合格」不同）
     * @param orderPassRate   单级合格率
     * @param secPerRow       每行平均秒数，行数为 0 时 null
     * @param minutesPerOrder 每单平均录入分钟数
     * @param flags           提示语，空表示没有异常
     * @param level           SUSPECT / WATCH / OK
     */
    public record Row(Raw raw, Double ngRate, Double orderPassRate, Double secPerRow,
                      Double minutesPerOrder, List<String> flags, String level) {
    }

    public record Result(List<Row> rows, Row total, Double baselineNgRate, String summary) {
    }

    public static Result analyze(List<Raw> raws) {
        if (raws == null) {
            raws = List.of();
        }

        Raw totalRaw = Raw.empty();
        for (Raw r : raws) {
            totalRaw = totalRaw.plus(r);
        }
        // 基线用「全员合计」，含此人自己。用「其他人」做基线时，人少会有 1~2 单的人
        // 把基线拽得乱跳；合计基线的含义是「我们厂的整体水平」，读起来也直白。
        Double baseline = MonthlyReport.ratio(totalRaw.pieceFail(), totalRaw.pieceTotal());
        // 全组都没判出过不良时，「零不良」不构成信号——那是产品好，不是记录问题
        boolean cohortHasDefect = totalRaw.pieceFail() > 0;
        boolean canCompare = raws.size() >= 2 && baseline != null && baseline > 0;

        List<Row> rows = new ArrayList<>(raws.size());
        int suspects = 0;
        int watches = 0;
        for (Raw r : raws) {
            Double ngRate = MonthlyReport.ratio(r.pieceFail(), r.pieceTotal());
            Double secPerRow = rate(r.secTotal(), r.rowTotal(), 1);
            Double minPerOrder = rate(r.secTotal(), r.orders() * 60L, 1);

            List<Flag> found = new ArrayList<>(2);
            if (cohortHasDefect && r.orders() >= ZERO_DEFECT_MIN_ORDERS && r.pieceFail() == 0) {
                found.add(new Flag(LEVEL_SUSPECT, r.orders() + " 单零不良（同组已判出 "
                        + totalRaw.pieceFail() + " 件不良），建议抽 1 单复核"));
            }
            if (r.rowTotal() >= RUSHED_MIN_ROWS && secPerRow != null && secPerRow < RUSHED_SEC_PER_ROW) {
                found.add(new Flag(LEVEL_SUSPECT, "平均每行 " + fmt(secPerRow) + " 秒，共 "
                        + r.rowTotal() + " 行，难以逐项实测"));
            }
            if (canCompare && r.orders() >= COMPARE_MIN_ORDERS && ngRate != null) {
                if (ngRate < baseline * LOW_RATIO) {
                    found.add(new Flag(LEVEL_WATCH, "不良率 " + pct(ngRate)
                            + "，不足同组（" + pct(baseline) + "）的三分之一"));
                } else if (ngRate > baseline * HIGH_RATIO && r.pieceFail() >= HIGH_MIN_FAIL_PIECES) {
                    found.add(new Flag(LEVEL_WATCH, "不良率 " + pct(ngRate)
                            + "，是同组（" + pct(baseline) + "）的两倍以上，"
                            + "可能是手法/量具差异，也可能判得过细"));
                }
            }

            String level = levelOf(found);
            if (LEVEL_SUSPECT.equals(level)) {
                suspects++;
            } else if (LEVEL_WATCH.equals(level)) {
                watches++;
            }
            rows.add(new Row(r, ngRate, MonthlyReport.ratio(r.passOrders(), r.orders()),
                    secPerRow, minPerOrder, found.stream().map(Flag::text).toList(), level));
        }

        Row totalRow = new Row(totalRaw,
                MonthlyReport.ratio(totalRaw.pieceFail(), totalRaw.pieceTotal()),
                MonthlyReport.ratio(totalRaw.passOrders(), totalRaw.orders()),
                rate(totalRaw.secTotal(), totalRaw.rowTotal(), 1),
                rate(totalRaw.secTotal(), totalRaw.orders() * 60L, 1),
                List.of(), LEVEL_OK);

        return new Result(rows, totalRow, baseline,
                summary(raws.size(), totalRaw, baseline, suspects, watches));
    }

    /**
     * 取最严重的那个级别。SUSPECT &gt; WATCH &gt; OK：
     * 「记录可能没做过」比「判得偏松/偏严」严重得多，一个人同时命中两类时按前者报。
     */
    private static String levelOf(List<Flag> flags) {
        if (flags.isEmpty()) {
            return LEVEL_OK;
        }
        for (Flag f : flags) {
            if (LEVEL_SUSPECT.equals(f.level())) {
                return LEVEL_SUSPECT;
            }
        }
        return LEVEL_WATCH;
    }

    private static String summary(int staff, Raw total, Double baseline, int suspects, int watches) {
        if (total.orders() == 0) {
            return "这段时间没有已提交的检验单，没有什么可分析的。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(staff).append(" 名检验员共提交 ").append(total.orders()).append(" 张单、受检 ")
                .append(total.pieceTotal()).append(" 件");
        if (baseline != null) {
            sb.append("，件级不良率 ").append(pct(baseline));
        }
        sb.append("。");
        if (suspects > 0) {
            sb.append("其中 ").append(suspects)
                    .append(" 人的记录存在可信度提示（零不良或录入过快），建议各抽一单复核——"
                            + "抽查是复核记录，不是查人。");
        } else if (watches > 0) {
            sb.append(watches).append(" 人的不良率与同组差异较大，可结合各自检的产品结构看一下。");
        } else {
            sb.append("未发现异常信号。");
        }
        return sb.toString();
    }

    /** 保留指定小数位；分母 0 或为负返回 null，不返回 0 */
    static Double rate(long part, long total, int scale) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String fmt(Double v) {
        return v == null ? "-" : BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 比率 → 「2.1%」 */
    static String pct(Double v) {
        if (v == null) {
            return "-";
        }
        return BigDecimal.valueOf(v * 100).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }
}
