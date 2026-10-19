package com.qc.inspect.judge;

/**
 * 枚举型检验项的选项。
 *
 * @param code 选项编码，实测值存该编码
 * @param name 显示名称
 * @param pass 该选项是否判为合格
 */
public record EnumOption(String code, String name, boolean pass) {
}
