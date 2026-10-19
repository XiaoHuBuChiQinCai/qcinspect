package com.qc.inspect.judge;

/**
 * 测量方式：一个检验项录入几个实测值。
 */
public enum ValueMode {
    /** 单值：只测一个点 */
    SINGLE,
    /** 多点：按 pointCount 录入多个测点，每点都需判定合格 */
    MULTIPLE
}
