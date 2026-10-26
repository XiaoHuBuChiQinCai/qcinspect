package com.qc.inspect.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 待检批次的分级判定。
 *
 * <p>批次建了、料到了，却一直没人开检验单——这是质检体系里最贵的一种漏。
 * 它不像不良品那样有迹可循：不良至少说明有人检过，漏检是整批货凭空跳过了检验环节，
 * 合格率报表上完全看不出来（没检的东西不进分母）。来料没检直接投产、成品没检直接出货，
 * 等客户投诉回来才知道，那时候追溯链已经断在源头。
 *
 * <p>所以这里判定的是「这个批次卡住了、需要人去做点什么」，而不是「批次状态是什么」。
 *
 * <p>三档，从重到轻：
 * <ol>
 *   <li>{@link #LEVEL_SHIPPED} 已经出货了，却一条有效检验结论都没有。这是违反
 *       「不检验不出货」的既成事实，事后只能补记录或判让步，必须点名</li>
 *   <li>{@link #LEVEL_OVERDUE} 停留超过阈值天数还没开单</li>
 *   <li>{@link #LEVEL_DUE} 昨天建的、今天还没开单</li>
 * </ol>
 *
 * <p>为什么当天建批不报警：下午到货、下午建批、明天一早检，这是正常节奏。
 * 把当天建批也算进去，页面每天上午都会挂一排「漏检」，用不了三天就没人看了——
 * 一个天天误报的提醒等于没有提醒。
 */
public final class PendingBatchInsight {

    /** 已出货但没有有效检验结论 */
    public static final String LEVEL_SHIPPED = "SHIPPED";
    /** 停留超过阈值天数仍未开单 */
    public static final String LEVEL_OVERDUE = "OVERDUE";
    /** 已过一天，尚未超过阈值 */
    public static final String LEVEL_DUE = "DUE";
    /** 当天建批，正常节奏 */
    public static final String LEVEL_OK = "OK";

    /** 默认宽限 2 天：当天建批当天检是常态，隔一天可能是排产，隔两天说明没人管 */
    public static final int DEFAULT_DAYS = 2;

    /** 阈值上限。填个 9999 就等于关掉这个功能，允许但不鼓励 */
    public static final int MAX_DAYS = 90;

    /** 单次返回上限。漏检批次多到这个量级，说明流程本身出了问题，列表再长也没人逐条看 */
    public static final int MAX_ROWS = 500;

    private PendingBatchInsight() {
    }

    /**
     * @param idleDays      已停留天数（从到货时间算，没填到货时间就从建批时间算）
     * @param thresholdDays 超期阈值
     * @param voidLots      该批次被作废的检验单数
     * @param shipments     有效出货单数（已红冲的不算）
     * @param shippedQty    累计出货件数
     */
    public static Verdict judge(int idleDays, int thresholdDays, int voidLots,
                                int shipments, int shippedQty) {
        List<String> flags = new ArrayList<>(3);

        // 已经出货却没有任何有效结论：先摆这一条，它比「几天没检」严重一个量级
        if (shipments > 0) {
            flags.add("已出货 " + shipments + " 单共 " + shippedQty + " 件，却没有一条有效的检验结论");
        }
        // 检过又作废：批次看着像「在检」，实际一张有效单都没有，最容易被忽略
        if (voidLots > 0) {
            flags.add("有 " + voidLots + " 张检验单已作废，批次目前没有任何有效结论");
        }

        String level;
        if (shipments > 0) {
            level = LEVEL_SHIPPED;
        } else if (idleDays >= Math.max(thresholdDays, 1)) {
            level = LEVEL_OVERDUE;
        } else if (idleDays >= 1) {
            level = LEVEL_DUE;
        } else {
            level = LEVEL_OK;
        }

        // 停留天数单独补一句，不塞进上面的分支：已出货的批次往往也躺了半个月，
        // 只报「已出货未检」会让人以为它是今天刚发出去的
        if (LEVEL_OK.equals(level)) {
            // 已经有作废提示时不再说「正常节奏」，两句话放一起自相矛盾
            if (flags.isEmpty()) {
                flags.add("今天刚建批，正常节奏");
            }
        } else if (idleDays >= 1) {
            flags.add("已停留 " + idleDays + " 天，仍未开检验单");
        }

        return new Verdict(level, List.copyOf(flags), !LEVEL_OK.equals(level));
    }

    /** 排序权重：越小越该先看 */
    public static int rank(String level) {
        return switch (level) {
            case LEVEL_SHIPPED -> 0;
            case LEVEL_OVERDUE -> 1;
            case LEVEL_DUE -> 2;
            default -> 3;
        };
    }

    /**
     * @param level  三档取一，见常量
     * @param flags  给人看的提示，按严重度排列，可直接展示
     * @param urgent 是否需要在待办里占一个数
     */
    public record Verdict(String level, List<String> flags, boolean urgent) {
    }
}
