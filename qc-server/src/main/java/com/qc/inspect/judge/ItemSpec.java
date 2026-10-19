package com.qc.inspect.judge;

import java.math.BigDecimal;
import java.util.List;

/**
 * 检验项的判定配置，来自检验明细里的快照字段，与数据库实体解耦。
 *
 * @param valueType     数据类型
 * @param valueMode     单值 / 多点
 * @param pointCount    多点时的测点数量，单值时忽略
 * @param minValue      数值型下限，可为空（单边约束）
 * @param maxValue      数值型上限，可为空
 * @param options       枚举型选项，布尔/数值/文本型可为空
 * @param boolPassValue 布尔型判为合格的值
 */
public record ItemSpec(
        ValueType valueType,
        ValueMode valueMode,
        int pointCount,
        BigDecimal minValue,
        BigDecimal maxValue,
        List<EnumOption> options,
        boolean boolPassValue
) {

    /** 单值数值项快捷构造 */
    public static ItemSpec number(BigDecimal min, BigDecimal max) {
        return new ItemSpec(ValueType.NUMBER, ValueMode.SINGLE, 1, min, max, null, true);
    }

    /** 多点数值项快捷构造 */
    public static ItemSpec numberMulti(int points, BigDecimal min, BigDecimal max) {
        return new ItemSpec(ValueType.NUMBER, ValueMode.MULTIPLE, points, min, max, null, true);
    }

    /** 枚举项快捷构造 */
    public static ItemSpec enums(List<EnumOption> options) {
        return new ItemSpec(ValueType.ENUM, ValueMode.SINGLE, 1, null, null, options, true);
    }

    /** 布尔项快捷构造：okValue 为「录入该值判为合格」 */
    public static ItemSpec bool(boolean okValue) {
        return new ItemSpec(ValueType.BOOL, ValueMode.SINGLE, 1, null, null, null, okValue);
    }

    /** 文本项快捷构造 */
    public static ItemSpec text() {
        return new ItemSpec(ValueType.TEXT, ValueMode.SINGLE, 1, null, null, null, true);
    }

    /** 期望录入的测点数量 */
    public int expectedPointCount() {
        return valueMode == ValueMode.MULTIPLE ? Math.max(pointCount, 1) : 1;
    }
}
