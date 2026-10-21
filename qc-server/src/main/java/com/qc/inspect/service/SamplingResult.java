package com.qc.inspect.service;

/**
 * 一次抽样计算的结论。
 *
 * <p>建单时把 {@code basis} 原样写进 {@code qc_inspection.sampling_basis}，
 * 方案表日后调整不会改变已建单据的判定依据。
 *
 * @param mode       计算方式：FULL 全检 / FIXED 固定样本量 / AQL 查表
 * @param lotQty     批量 N（快照）
 * @param sampleQty  本次要验的件数 n
 * @param acceptQty  接收数 Ac
 * @param codeLetter 样本量字码，仅 AQL 模式有值
 * @param basis      人读的抽样依据
 */
public record SamplingResult(
        String mode,
        int lotQty,
        int sampleQty,
        int acceptQty,
        String codeLetter,
        String basis) {
}
