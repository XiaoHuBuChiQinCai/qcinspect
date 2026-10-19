package com.qc.inspect.judge;

import java.util.List;

/**
 * 待判定的检验行输入。
 *
 * @param lineId     明细 ID
 * @param itemId     检验项 ID
 * @param pieceNo    件号，从 1 开始。同一批抽 N 件时，第 i 件的所有项都用同一个件号
 * @param itemName   检验项名称，用于生成可读的判定说明
 * @param isCritical 是否关键项
 * @param spec       判定配置
 * @param values     实测值
 * @param textValue  文本型录入内容
 */
public record JudgeTarget(
        Long lineId,
        Long itemId,
        int pieceNo,
        String itemName,
        boolean isCritical,
        ItemSpec spec,
        List<String> values,
        String textValue
) {

    /** 单件场景的便捷构造，件号恒为 1 */
    public static JudgeTarget ofPiece1(Long lineId, Long itemId, String itemName, boolean isCritical,
                                       ItemSpec spec, List<String> values, String textValue) {
        return new JudgeTarget(lineId, itemId, 1, itemName, isCritical, spec, values, textValue);
    }
}
