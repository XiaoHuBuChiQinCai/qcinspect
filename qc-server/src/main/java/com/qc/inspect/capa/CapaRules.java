package com.qc.inspect.capa;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * CAPA 的规则，与数据库和框架无关。
 *
 * <p>这里管的只有一件事：<b>什么时候允许走到下一步</b>。做成纯函数的理由和返修、GR&R 一样——
 * 同一套判断要在服务层、数据库 CHECK、以及「为什么不让点这个按钮」的提示里各出现一次，
 * 分散写三遍必然有一天对不上，而对不上的那天恰好是外审翻记录的那天。
 *
 * <p>状态机（刻意短）：
 * <pre>
 *   OPEN 待分析 ──start──→ DOING 措施执行中 ──submitVerify──→ VERIFYING 待验证
 *                              ↑                                  │
 *                              └────── verify(无效) ──────────────┤
 *                                                                  └─ verify(有效) → CLOSED
 *   任意未关闭态 ──cancel(理由)──→ CANCELLED
 * </pre>
 *
 * <p>为什么不设审批：措施有没有用不是批出来的，是<b>验</b>出来的。加两级审批只会让
 * 一张单在系统里走三周，而问题还在产线上照着原样发生。
 */
public final class CapaRules {

    public static final String OPEN = "OPEN";
    public static final String DOING = "DOING";
    public static final String VERIFYING = "VERIFYING";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELLED = "CANCELLED";

    /** 默认期限：两周。一周常常不够查根因，一个月又容易拖成「下个月再说」 */
    public static final int DEFAULT_DUE_DAYS = 14;

    private CapaRules() {
    }

    /** 判断结果。message 是给人看的一句话，不是给程序判断用的 */
    public record Check(boolean ok, String message) {

        /** 不能叫 ok()：会和 record 的取值方法 ok() 撞名，编译期直接报错 */
        public static Check pass() {
            return new Check(true, null);
        }

        public static Check fail(String message) {
            return new Check(false, message);
        }
    }

    public static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case OPEN -> "待分析";
            case DOING -> "措施执行中";
            case VERIFYING -> "待验证";
            case CLOSED -> "已关闭";
            case CANCELLED -> "已撤销";
            default -> "未知状态";
        };
    }

    /** 还开着（会进待办、会算超期）。关闭和撤销都不再占人精力 */
    public static boolean isOpen(String status) {
        return OPEN.equals(status) || DOING.equals(status) || VERIFYING.equals(status);
    }

    // ------------------------------------------------------------------ 建单

    /**
     * 建单前的必填检查。
     *
     * <p>只要求「问题说得清、有人负责」。根因和措施这时候当然还没有——查原因要时间，
     * 建单这一下就逼着人编一个，只会编出「员工质量意识不足」这种正确而无用的话。
     */
    public static Check checkCreate(String title, String problem, String owner) {
        if (isBlank(title)) {
            return Check.fail("标题不能为空：一句话说清是什么毛病，以后靠它认人");
        }
        if (title.trim().length() > 200) {
            return Check.fail("标题太长了（超过 200 字），把细节写进问题描述里");
        }
        if (isBlank(problem)) {
            return Check.fail("问题描述不能为空：什么产品、什么不良、影响了多少件，写清楚才能查");
        }
        if (isBlank(owner)) {
            return Check.fail("必须指定责任人。没人负责的措施不会有人做");
        }
        return Check.pass();
    }

    // ------------------------------------------------------------------ 流转

    /** 待分析 → 措施执行中 */
    public static Check checkStart(String status) {
        if (CLOSED.equals(status)) {
            return Check.fail("这条措施已经关闭了。再犯就另开一条，并在标题里注明是再犯——"
                    + "把旧单重新打开，会抹掉它上一次「验证有效」的结论");
        }
        if (CANCELLED.equals(status)) {
            return Check.fail("这条措施已撤销，不能再启动。要接着做请重新建一条");
        }
        if (VERIFYING.equals(status)) {
            return Check.fail("这条已经提交验证了，等验证结论就行；如果要补充措施，"
                    + "让验证人先判「无效」退回来");
        }
        if (DOING.equals(status)) {
            return Check.fail("这条已经在执行中了，不用重复开始");
        }
        return Check.pass();
    }

    /**
     * 措施执行中 → 待验证。
     *
     * <p>这是整条链上最要紧的一道门：<b>没写根因就不许提交验证</b>。
     * 现场最常见的偷懒是「把它返工掉就完了，原因回头再说」——回头没有那一天，
     * 下个月同一个不良代码又出现在帕累托第一行。没有根因的措施只是处理，不是纠正。
     */
    public static Check checkSubmitVerify(String status, String rootCause, String actionPlan) {
        if (CLOSED.equals(status) || CANCELLED.equals(status)) {
            return Check.fail("这条措施已经结束了，不能再提交验证");
        }
        if (VERIFYING.equals(status)) {
            return Check.fail("已经提交验证了，不用重复提交");
        }
        if (OPEN.equals(status)) {
            return Check.fail("还没开始就提交验证？先点「开始处理」，把根因和措施填进去");
        }
        if (isBlank(rootCause)) {
            return Check.fail("根本原因还没写。措施做完了却说不清为什么发生，"
                    + "等于只是把这批坏件处理掉了，下个月还会照原样再来一次");
        }
        if (isBlank(actionPlan)) {
            return Check.fail("纠正措施还没写：针对这个根因，具体做了什么");
        }
        return Check.pass();
    }

    /**
     * 验证：有效则关闭，无效则退回「措施执行中」。
     *
     * <p>刻意不做「验证通过率」这种指标——一旦验证被当成 KPI，就没有人会判无效，
     * 于是所有措施都「有效」，CAPA 系统退化成一张安慰表。
     */
    public static Check checkVerify(String status, Boolean effective, String verifyNote) {
        if (!VERIFYING.equals(status)) {
            return Check.fail("只有「待验证」的措施才需要验证。当前状态：" + statusText(status));
        }
        if (effective == null) {
            return Check.fail("要明确判「有效」还是「无效」，不能只写一段话");
        }
        if (isBlank(verifyNote)) {
            return Check.fail("验证结论不能为空：看了什么数据、连续几个批次没再出现，写清楚。"
                    + "「已改善」这种话三个月后没人知道当时看了什么");
        }
        return Check.pass();
    }

    /** 撤销：任何未关闭的都能撤，但要写清为什么 */
    public static Check checkCancel(String status, String reason) {
        if (CLOSED.equals(status)) {
            return Check.fail("已经关闭的措施不能撤销。它上面的验证结论是历史事实，删掉就等于没有发生过");
        }
        if (CANCELLED.equals(status)) {
            return Check.fail("这条已经撤销过了");
        }
        if (isBlank(reason)) {
            return Check.fail("撤销要写理由：是问题本身搞错了，还是并入别的措施了");
        }
        return Check.pass();
    }

    // ------------------------------------------------------------------ 期限

    /**
     * 只有还开着的才算超期。
     *
     * <p>关闭日之后当然「超过了要求完成日」，但那不是超期，是历史。
     */
    public static boolean overdue(LocalDate dueDate, String status, LocalDate today) {
        return dueDate != null && isOpen(status) && dueDate.isBefore(today);
    }

    /** 超期天数。未超期返回 0，不返回负数——负数在页面上会被显示成「超期 -3 天」 */
    public static int overdueDays(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || today == null) {
            return 0;
        }
        long d = ChronoUnit.DAYS.between(dueDate, today);
        return d > 0 ? (int) d : 0;
    }

    public static LocalDate defaultDueDate(LocalDate today) {
        return today.plusDays(DEFAULT_DUE_DAYS);
    }

    /**
     * 关闭时该说的话。
     *
     * <p>只做纠正不做预防，是全厂范围内最常见的半截活：这台机器调好了，另外三台同型号
     * 的照样会犯。所以「预防措施空着」不拦着关闭（很多时候确实只有这一处），
     * 但必须提醒一句，让人自己决定要不要补。
     */
    public static String closingNote(String preventPlan) {
        if (isBlank(preventPlan)) {
            return "只做了纠正、没写预防措施。这类问题如果在别的产品、别的机台上也存在，"
                    + "加一句预防会更值——不填不影响关闭";
        }
        return null;
    }

    // ------------------------------------------------------------ 什么时候该立措施

    /**
     * 「反复出现」的批次门槛。
     *
     * <p>用<b>批次数</b>而不是出现次数来判：同一批里出现十次，是那一批的来料或设备问题，
     * 先把那批处理掉就完了；三个批次各出现一次，说明换料、换班、换模之后它照样发生，
     * 那才是系统性原因。这两个数长得很像，含义完全不同。
     */
    public static final int SUGGEST_BATCHES = 3;

    /**
     * 一条不良簇该不该出现在「建议立措施」里。
     *
     * @param batches           近窗口内出现的批次数
     * @param occ               近窗口内出现的次数
     * @param hasOpenCapa       已有还开着的措施
     * @param hadEffectiveCapa  有过判「验证有效」且已关闭的措施
     */
    public record Advice(boolean suggest, boolean regressed, String note) {

        public static Advice quiet() {
            return new Advice(false, false, null);
        }
    }

    public static Advice advice(long batches, long occ, boolean hasOpenCapa, boolean hadEffectiveCapa) {
        // 已经在改的不催：再列一条「建议立措施」出来，人会以为系统不知道他正在做
        if (hasOpenCapa) {
            return Advice.quiet();
        }
        // 改过、判过有效、现在又出现——这是整块功能里最该被看见的一条。
        // 放宽到 occ >= 1：哪怕只又冒了一次，也说明上次那条措施没打到根因上
        if (hadEffectiveCapa && occ > 0) {
            return new Advice(true, true,
                    "上次改完判过「验证有效」，最近又出现 " + occ + " 次（" + batches
                            + " 个批次）。别再照着上一套做一遍——同一套做法再来一次，"
                            + "多半还是同样的结果。先弄清楚它到底在什么条件下才发生");
        }
        if (batches >= SUGGEST_BATCHES) {
            return new Advice(true, false,
                    "近 " + batches + " 个批次都有它，累计 " + occ + " 次。"
                            + "跨批次还在发生，说明不是某一批的偶发，查一次根因比反复挑拣划算");
        }
        // 剩下的（哪怕次数不少）先不推：都挤在一两批里的，多半是那批货的事
        return Advice.quiet();
    }

    // ------------------------------------------------------------ 什么时候该催

    /** 到期前几天进提醒窗口。三天够人排一次活，一周会变成「还早」 */
    public static final int DUE_SOON_DAYS = 3;

    /**
     * 距到期还有几天。已关闭、已撤销、或没定期限的返回 {@code null}。
     *
     * <p>返回负数表示已超期——那是「已经晚了」，和「快到了」不是同一句话，调用方要分开说。
     */
    public static Integer dueInDays(LocalDate dueDate, String status, LocalDate today) {
        if (dueDate == null || today == null || !isOpen(status)) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(today, dueDate);
    }

    /**
     * 该催了：还没超期，但已经进了提醒窗口。
     *
     * <p>已提交验证的不催（{@code VERIFYING}）—— 期限是给「改」定的，他已经交上来了，
     * 这时候催他等于催错人；真正压着的是验证人，那一头由待办管。
     */
    public static boolean dueSoon(LocalDate dueDate, String status, LocalDate today) {
        if (!OPEN.equals(status) && !DOING.equals(status)) {
            return false;
        }
        Integer d = dueInDays(dueDate, status, today);
        return d != null && d >= 0 && d <= DUE_SOON_DAYS;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
