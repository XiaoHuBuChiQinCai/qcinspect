package com.qc.inspect.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.entity.QcComplaint;

/**
 * 客诉查询条件。列表页和导出共用同一份，避免「屏幕 3 条、CSV 30 条」。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@code keyword} 同时匹配客诉单号、客户名、批次号——接电话的人手里
 *       通常只有一个号，或者是客户报的批次号</li>
 *   <li>{@code open} 为 true 时只看未关闭的（待处理 + 处理中），工作台用</li>
 * </ul>
 */
public record ComplaintQuery(String status, String severity, String keyword,
                             String from, String to, Boolean open) {

    public LambdaQueryWrapper<QcComplaint> toWrapper() {
        String kw = blankToNull(keyword);
        String sev = blankToNull(severity);
        String dayFrom = blankToNull(from);
        String dayTo = blankToNull(to);

        return new LambdaQueryWrapper<QcComplaint>()
                .eq(!isBlank(status), QcComplaint::getStatus, status)
                .eq(sev != null, QcComplaint::getSeverity, sev)
                .ne(Boolean.TRUE.equals(open), QcComplaint::getStatus, QcComplaint.STATUS_CLOSED)
                .and(kw != null, w -> w
                        .like(QcComplaint::getComplaintNo, kw)
                        .or()
                        .like(QcComplaint::getCustomer, kw)
                        // 批次号不在本表上，用 EXISTS 而不是先查一批 id 再 IN：
                        // 模糊匹配可能命中几百个批次，IN 列表会把 SQL 撑爆。
                        // 占位符必须 CAST 成 text，否则 PostgreSQL 推断不出参数类型。
                        .or()
                        .apply("EXISTS (SELECT 1 FROM qc_batch b WHERE b.id = qc_complaint.batch_id"
                                + " AND b.batch_no LIKE CONCAT('%', CAST({0} AS text), '%'))", kw))
                .apply(dayFrom != null, "complaint_date >= CAST({0} AS date)", dayFrom)
                .apply(dayTo != null, "complaint_date <= CAST({0} AS date)", dayTo);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
