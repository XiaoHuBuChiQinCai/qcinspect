package com.qc.inspect.judge;

/**
 * 单个检验项的判定结果。
 *
 * @param pass       是否合格
 * @param reason     判定说明，不合格时写入原因，合格时可为空
 * @param incomplete 是否属于「数据不完整」而非「指标做坏了」
 */
public record JudgeOutcome(boolean pass, String reason, boolean incomplete) {

    public static JudgeOutcome ok() {
        return new JudgeOutcome(true, null, false);
    }

    public static JudgeOutcome ok(String reason) {
        return new JudgeOutcome(true, reason, false);
    }

    /** 指标不达标：超规、枚举判不合格、布尔方向不对 */
    public static JudgeOutcome ng(String reason) {
        return new JudgeOutcome(false, reason, false);
    }

    /**
     * 数据不完整：没录、测点不够、不是数字、选项对不上、标准没配。
     *
     * <p>和 {@link #ng} 分开，是因为两者对整批的意义完全不同：前者是「判不了」，
     * 后者是「判了不合格」。混为一谈会带来一个很严重的后果 —— 漏填产生的「不合格件」
     * 会被允收数 Ac 当作正常的不良品消化掉，于是「3 件关键尺寸根本没测」的批次
     * 因为 Ac 够大而判合格。
     */
    public static JudgeOutcome missing(String reason) {
        return new JudgeOutcome(false, reason, true);
    }
}
