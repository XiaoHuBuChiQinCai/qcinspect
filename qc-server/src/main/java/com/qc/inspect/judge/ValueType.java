package com.qc.inspect.judge;

/**
 * 检验项数据类型，决定判定策略与前端录入控件。
 */
public enum ValueType {
    /** 数值型：录入数字，按上下限判定 */
    NUMBER,
    /** 枚举型：从选项中选择，按选项配置的 pass 判定 */
    ENUM,
    /** 布尔型：有/无，按 boolPassValue 判定 */
    BOOL,
    /** 文本型：仅记录，不参与判定 */
    TEXT
}
