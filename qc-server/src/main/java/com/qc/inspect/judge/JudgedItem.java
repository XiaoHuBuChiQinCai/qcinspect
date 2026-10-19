package com.qc.inspect.judge;

/**
 * 单行判定结果，回写检验明细用。
 *
 * @param lineId   明细 ID
 * @param pieceNo  件号
 * @param itemName 检验项名称
 * @param pass     是否合格
 * @param reason   判定说明
 */
public record JudgedItem(Long lineId, int pieceNo, String itemName, boolean pass, String reason) {
}
