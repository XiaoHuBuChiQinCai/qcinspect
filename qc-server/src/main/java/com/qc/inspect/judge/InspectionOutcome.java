package com.qc.inspect.judge;

import java.util.List;

/**
 * 整批判定结果。两层结构：先按件判，再按件数判批。
 *
 * @param items           逐行结果（件 × 项）
 * @param pieces          逐件结果
 * @param ngCount         不合格行总数（件 × 项 的计数），含数据不完整的行
 * @param criticalNgCount 关键项不合格行数
 * @param passedQty       合格件数
 * @param failedQty       不合格件数
 * @param acceptQty       允收数 Ac（本批允许的最大不合格件数）
 * @param incompleteCount 数据不完整的行数（没录、测点不够、不是数字、配置缺失）。
 *                        大于 0 时整批直判不合格，且 {@code acceptQty} 不适用
 * @param pass            整批是否合格
 * @param reason          整批判定说明
 */
public record InspectionOutcome(
        List<JudgedItem> items,
        List<PieceOutcome> pieces,
        int ngCount,
        int criticalNgCount,
        int passedQty,
        int failedQty,
        int acceptQty,
        int incompleteCount,
        boolean pass,
        String reason
) {
}
