package com.qc.inspect.judge;

/**
 * 单件判定结果。一个批次抽 N 件，就有 N 个本记录。
 *
 * @param pieceNo         件号，从 1 开始
 * @param pass            该件是否合格
 * @param ngCount         该件不合格项数
 * @param criticalNgCount 该件关键项不合格数
 * @param reason          判定说明
 */
public record PieceOutcome(
        int pieceNo,
        boolean pass,
        int ngCount,
        int criticalNgCount,
        String reason
) {
}
