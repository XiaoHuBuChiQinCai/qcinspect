package com.qc.inspect.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.entity.QcInspection;

/**
 * 检验单查询条件。
 *
 * <p>为什么单独抽一个类：列表分页和 CSV 导出必须用<strong>同一份</strong>过滤逻辑。
 * 以前两处各写一遍 {@code eq}，加一个筛选条件时只改了列表没改导出，
 * 结果就是「屏幕上 3 条、导出的 CSV 里有 30 条」——这种账对不上比没有导出更糟。
 * 现在过滤只在这里定义一次，两处都调 {@link #toWrapper()}。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code keyword} 同时匹配检验单号和批次号，因为主管找人时手里通常只有一个号，
 *       记不清那是单号还是批次号</li>
 *   <li>{@code from}/{@code to} 按 {@code coalesce(inspected_at, created_at)} 过滤：
 *       草稿没有检验时间，但翻旧账时也得能捞出来，否则草稿在列表里是隐形的</li>
 * </ul>
 */
public record InspectionQuery(String status, Long batchId, Long productId,
                              String keyword, String inspector, String result,
                              String from, String to) {

    public LambdaQueryWrapper<QcInspection> toWrapper() {
        String kw = blankToNull(keyword);
        String dayFrom = blankToNull(from);
        String dayTo = blankToNull(to);

        return new LambdaQueryWrapper<QcInspection>()
                .eq(!isBlank(status), QcInspection::getStatus, status)
                .eq(batchId != null, QcInspection::getBatchId, batchId)
                .eq(productId != null, QcInspection::getProductId, productId)
                .eq(!isBlank(result), QcInspection::getResult, result)
                .like(!isBlank(inspector), QcInspection::getInspectorName, inspector)
                .and(kw != null, w -> w
                        .like(QcInspection::getInspectionNo, kw)
                        .or()
                        // 批次号不是本表字段，用 EXISTS 而不是「先查 id 再 IN」：
                        // 模糊匹配可能命中几千个批次，IN 列表会把 SQL 撑爆。
                        // {0} 是 MyBatis 的预编译占位符，不做字符串拼接，没有注入面。
                        // 占位符必须 CAST 成 text：PostgreSQL 的 CONCAT 接受任意类型，
                        // 遇到没类型的占位符无法推断，直接报「无法确定参数 $2 的数据类型」。
                        .apply("EXISTS (SELECT 1 FROM qc_batch b WHERE b.id = qc_inspection.batch_id"
                                + " AND b.batch_no LIKE CONCAT('%', CAST({0} AS text), '%'))", kw))
                .apply(dayFrom != null,
                        "coalesce(inspected_at, created_at) >= CAST({0} AS date)", dayFrom)
                .apply(dayTo != null,
                        "coalesce(inspected_at, created_at) < CAST({0} AS date) + interval '1 day'", dayTo);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
