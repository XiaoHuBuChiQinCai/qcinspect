package com.qc.inspect.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.entity.QcInspectionLog;

/**
 * 操作日志查询条件。
 *
 * <p>为什么要有全局日志入口：{@code GET /inspections/{id}/logs} 只能「先知道是哪张单，
 * 再去看它发生过什么」。而内审和管理者的问题是反过来的——「上个月谁作废过单」、
 * 「已归档的单被谁解锁过」。数据一直都在 {@code qc_inspection_log} 里，只是没有窗口。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code keyword} 同时匹配检验单号和批次号，因为来查的人手里常常只有一个号</li>
 *   <li>{@code from}/{@code to} 按日志发生时间过滤，闭区间到「当天 23:59:59」</li>
 * </ul>
 */
public record AuditQuery(String action, String operator, String keyword,
                         String from, String to) {

    public LambdaQueryWrapper<QcInspectionLog> toWrapper() {
        String kw = blankToNull(keyword);
        String op = blankToNull(operator);
        String dayFrom = blankToNull(from);
        String dayTo = blankToNull(to);

        return new LambdaQueryWrapper<QcInspectionLog>()
                .eq(!isBlank(action), QcInspectionLog::getAction, action)
                .like(op != null, QcInspectionLog::getOperator, op)
                .and(kw != null, w -> w
                        // 单号和批次号都不在日志表上，用 EXISTS 而不是「先查一批 id 再 IN」：
                        // 模糊匹配可能命中几千张单，IN 列表会把 SQL 撑爆。
                        // 占位符必须 CAST 成 text，否则 PostgreSQL 推断不出参数类型。
                        .apply("EXISTS (SELECT 1 FROM qc_inspection i"
                                + " WHERE i.id = qc_inspection_log.inspection_id"
                                + "   AND (i.inspection_no LIKE CONCAT('%', CAST({0} AS text), '%')"
                                + "        OR EXISTS (SELECT 1 FROM qc_batch b"
                                + "                   WHERE b.id = i.batch_id"
                                + "                     AND b.batch_no LIKE CONCAT('%', CAST({1} AS text), '%'))))",
                                kw, kw))
                .apply(dayFrom != null, "created_at >= CAST({0} AS date)", dayFrom)
                .apply(dayTo != null, "created_at < CAST({0} AS date) + interval '1 day'", dayTo);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
