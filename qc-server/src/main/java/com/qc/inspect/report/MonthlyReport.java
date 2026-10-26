package com.qc.inspect.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一段时期的检验指标，以及从指标派生的几个比率。
 *
 * <p>做成独立的 record 而不是直接在 Service 里算，是因为月报里最容易出错、
 * 又最不容易被发现的恰恰是这些除法：分母为 0 时该显示「-」还是 0%，
 * 环比该说「上升 1.3 个百分点」还是「上升 1.3%」，写错了报表照样能输出，
 * 但月底拿到会上是要被人当面质疑的。这里把它们变成可单测的纯函数。
 *
 * <p><b>「没有数据」与「零合格」必须分开。</b>分母为 0（这个月没有检验单）
 * 返回 {@code null}，前端显示「-」；只有真的做了检验且全不合格才是 0%。
 * 把两者混成一个 0，报表会出现「本月合格率 0%」这种吓人的假警报。
 *
 * @param orders        检验单数（只算已提交/已关闭）
 * @param passOrders    整单判合格的张数
 * @param pieceTotal    受检件数（各单样本量之和）
 * @param piecePass     合格件数
 * @param pieceFail     不合格件数
 * @param recheckOrders 其中返工复检单数
 */
public record MonthlyReport(
        long orders,
        long passOrders,
        long pieceTotal,
        long piecePass,
        long pieceFail,
        long recheckOrders
) {

    public static final MonthlyReport EMPTY = new MonthlyReport(0, 0, 0, 0, 0, 0);

    /** 这个月一张单都没有。空月不参与环比，也不该生成「下降 100%」的结论 */
    public boolean isEmpty() {
        return orders == 0 && pieceTotal == 0;
    }

    /** 件级合格率，0~1，保留 4 位。抽检场景以件级为准 */
    public Double yieldRate() {
        return ratio(piecePass, pieceTotal);
    }

    /** 单级合格率：整单判 PASS 的张数占比。用于和件级对照 */
    public Double orderYieldRate() {
        return ratio(passOrders, orders);
    }

    /** 返工复检率：返工复检单 / 全部检验单 */
    public Double recheckRate() {
        return ratio(recheckOrders, orders);
    }

    /**
     * 每百万件不良数（PPM）。客户和体系审核看的是这个数，不是百分比——
     * 98% 的合格率听起来还行，换算成 20000 PPM 就没人笑得出来了。
     */
    public Long ppm() {
        if (pieceTotal <= 0) {
            return null;
        }
        return BigDecimal.valueOf(pieceFail)
                .multiply(BigDecimal.valueOf(1_000_000L))
                .divide(BigDecimal.valueOf(pieceTotal), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * 与上期相比的合格率变化，单位是<b>百分点</b>。
     *
     * <p>96.0% → 97.3% 是「上升 1.3 个百分点」，不是「上升 1.3%」。
     * 后者会被读成「不良率改善 1.3%」，差着一个数量级的理解。
     */
    public Double yieldDeltaPp(MonthlyReport prev) {
        return deltaPp(yieldRate(), prev == null ? null : prev.yieldRate());
    }

    public Double orderYieldDeltaPp(MonthlyReport prev) {
        return deltaPp(orderYieldRate(), prev == null ? null : prev.orderYieldRate());
    }

    /** 通用比率：分母 0 返回 null，不做除零保护性返回 0 */
    public static Double ratio(long part, long total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static Double deltaPp(Double cur, Double prev) {
        if (cur == null || prev == null) {
            return null;
        }
        return BigDecimal.valueOf(cur - prev)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 从「按月 × 检验类型」的分组结果汇总出整月指标 */
    public static MonthlyReport sum(Iterable<MonthlyReport> parts) {
        long orders = 0, passOrders = 0, pieceTotal = 0, piecePass = 0, pieceFail = 0, recheck = 0;
        for (MonthlyReport p : parts) {
            orders += p.orders();
            passOrders += p.passOrders();
            pieceTotal += p.pieceTotal();
            piecePass += p.piecePass();
            pieceFail += p.pieceFail();
            recheck += p.recheckOrders();
        }
        return new MonthlyReport(orders, passOrders, pieceTotal, piecePass, pieceFail, recheck);
    }
}
