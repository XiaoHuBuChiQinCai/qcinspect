package com.qc.inspect.rework;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 返修报数与超期的判定规则。
 *
 * <p>纯逻辑、零框架依赖，所以能被 {@code mvn test} 直接覆盖。放在这里而不是写进
 * Service 的 if 里，是因为这套规则有两处要判：完工接口要拦，移动端在按「提交」
 * 之前也要先给提示。两处各写一遍必然有一天对不上——一处改了另一处忘改，
 * 手机上通过了、服务端拒了，用户不知道听谁的。
 *
 * <p>更要紧的是库层还有一道 CHECK（{@code reworked_qty + scrapped_qty = qty}）。
 * 这里是给人看的提示，那里是最后一道闸，两边口径必须一致。
 */
public final class ReworkBalance {

    /** 单张返修票的上限，与检验单的 {@code MAX_SAMPLE_QTY} 对齐，不让人一次送修一整托 */
    public static final int MAX_QTY = 500;

    private ReworkBalance() {
    }

    /**
     * 完工报数的校验结果。
     *
     * @param ok       是否可提交
     * @param message  不通过时的原因，直接拿去弹窗，不要让人再去猜「哪里错了」
     */
    public record Check(boolean ok, String message) {

        static Check pass() {
            return new Check(true, null);
        }

        static Check fail(String message) {
            return new Check(false, message);
        }
    }

    /**
     * 校验送修数量。
     *
     * @param qty 送修数量，可为空（调用方没带时）
     */
    public static Check checkQty(Integer qty) {
        if (qty == null) {
            return Check.fail("请填送修数量");
        }
        if (qty <= 0) {
            return Check.fail("送修数量必须大于 0");
        }
        if (qty > MAX_QTY) {
            return Check.fail("一张票最多 " + MAX_QTY + " 件。送修更多请按工位或班次拆成多张，"
                    + "一次修完记不清哪件是哪件");
        }
        return Check.pass();
    }

    /**
     * 校验完工报数：返好 + 返废 必须正好等于送修。
     *
     * <p>为什么是「正好等于」而不是「不超过」：少于送修数量意味着有几件没交代去向，
     * 那几件多半还在返修台上、或者被谁顺手扔了。车间里最怕的就是这种「说不清」——
     * 数目对不上时，系统里记的合格率、报废率全是错的，而且没人会去查。
     *
     * @param qty         送修数量
     * @param reworkedQty 返好件数
     * @param scrappedQty 返废件数
     */
    public static Check checkFinish(int qty, Integer reworkedQty, Integer scrappedQty) {
        if (reworkedQty == null || scrappedQty == null) {
            return Check.fail("返好件数和返废件数都要填。返废填 0 也行，但不能空着不交代");
        }
        if (reworkedQty < 0 || scrappedQty < 0) {
            return Check.fail("件数不能是负数");
        }
        int sum = reworkedQty + scrappedQty;
        if (sum < qty) {
            return Check.fail("返好 " + reworkedQty + " 件 + 返废 " + scrappedQty + " 件 = " + sum
                    + " 件，比送修的 " + qty + " 件少了 " + (qty - sum) + " 件。"
                    + "那几件去哪了要写清楚，不能凭空消失");
        }
        if (sum > qty) {
            return Check.fail("返好 " + reworkedQty + " 件 + 返废 " + scrappedQty + " 件 = " + sum
                    + " 件，比送修的 " + qty + " 件多了 " + (sum - qty) + " 件。"
                    + "多出来的件是不是记错了票");
        }
        return Check.pass();
    }

    /**
     * 是否超期。
     *
     * <p>只对还没结束的票判超期：已完工的票即使当初晚了两天，也没必要在列表上一直飘着红字。
     *
     * @param dueDate 要求完成日，为空视为没定期限（不算超期）
     * @param status  当前状态
     * @param today   今天（由服务端算好传进来，不让浏览器拿本地时间比）
     */
    public static boolean overdue(LocalDate dueDate, String status, LocalDate today) {
        if (dueDate == null || today == null) {
            return false;
        }
        if (!"OPEN".equals(status) && !"DOING".equals(status)) {
            return false;
        }
        return dueDate.isBefore(today);
    }

    /** 超期天数，未超期返回 0 */
    public static int overdueDays(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || today == null || !dueDate.isBefore(today)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(dueDate, today);
    }

    /**
     * 默认要求完成日。
     *
     * <p>不让人手填日期，是因为手填的结果通常是「今天」或者空着。给一个
     * 三天后作为默认值：够短，短到超期会立刻被发现；也够长，长到不至于
     * 开票当天就被标红而让人习惯性忽略红字。
     */
    public static LocalDate defaultDueDate(LocalDate today) {
        return today == null ? null : today.plusDays(3);
    }
}
