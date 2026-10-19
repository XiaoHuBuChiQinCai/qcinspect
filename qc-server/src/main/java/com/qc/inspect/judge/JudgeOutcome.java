package com.qc.inspect.judge;

/**
 * 单个检验项的判定结果。
 *
 * @param pass   是否合格
 * @param reason 判定说明，不合格时写入原因，合格时可为空
 */
public record JudgeOutcome(boolean pass, String reason) {

    public static JudgeOutcome ok() {
        return new JudgeOutcome(true, null);
    }

    public static JudgeOutcome ok(String reason) {
        return new JudgeOutcome(true, reason);
    }

    public static JudgeOutcome ng(String reason) {
        return new JudgeOutcome(false, reason);
    }
}
