package com.qc.inspect.service;

import com.qc.inspect.common.BizException;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.report.InspectorInsight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检验员维度统计：回答「这些记录是谁录的、录得可不可信」。
 *
 * <p>和月报的分工：月报按<b>产品/类型</b>看结果好不好，过程能力按<b>工序</b>看稳不稳，
 * 这一页按<b>人</b>看记录本身站不站得住。三张表加起来才凑得出「数据可信吗」的答案。
 *
 * <p>默认窗口取<b>近 90 天</b>而不是当月：检验员数量少、单据量不大时，
 * 当月往往只有几张单，谁多谁少全是噪声。90 天是个折中——够长能有统计意味，
 * 够短不会被半年前的旧习惯压住。
 */
@Service
@RequiredArgsConstructor
public class InspectorStatService {

    private static final ZoneOffset BIZ_OFFSET = ZoneOffset.ofHours(8);
    private static final ZoneId BIZ_ZONE = ZoneId.of("+08:00");

    /** 默认回看天数 */
    private static final int DEFAULT_DAYS = 90;
    /** 最长只看两年：再往前翻，人早就换了几轮，横向对比没有意义，也拖慢查询 */
    private static final int MAX_DAYS = 730;

    private final QcInspectionMapper inspectionMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> inspectors(String from, String to) {
        LocalDate today = LocalDate.now(BIZ_ZONE);
        // 前端传的是「看到哪天」，含当天；SQL 那边再用 次日 00:00 做开区间上界。
        // 参数语义按人的直觉走（含首含尾），边界换算只在这一个地方做，别处不再猜
        LocalDate end = parse(to, today, "结束日期");
        // 默认「近 90 天」是含首含尾的 90 天：今天往前数 90 天是 minusDays(89)，
        // 写成 minusDays(90) 会变成 91 天——差一天，页面上标的区间就和按钮不符
        LocalDate start = parse(from, end.minusDays(DEFAULT_DAYS - 1L), "开始日期");

        if (!start.isBefore(end) && !start.isEqual(end)) {
            throw BizException.of("开始日期必须早于结束日期：" + start + " → " + end);
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw BizException.of("时间跨度最多 " + MAX_DAYS + " 天（约两年），当前选了 " + days
                    + " 天。跨度太长时人员可能已经换过几轮，横向对比会失真");
        }

        List<Map<String, Object>> rows = inspectionMapper.inspectorStats(
                start.atStartOfDay().atOffset(BIZ_OFFSET).toString(),
                end.plusDays(1).atStartOfDay().atOffset(BIZ_OFFSET).toString());

        List<InspectorInsight.Raw> raws = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            raws.add(new InspectorInsight.Raw(
                    str(r.get("inspector")),
                    num(r.get("orders")),
                    num(r.get("piece_total")),
                    num(r.get("piece_pass")),
                    num(r.get("piece_fail")),
                    num(r.get("ng_count")),
                    num(r.get("critical_ng")),
                    num(r.get("pass_orders")),
                    num(r.get("recheck_orders")),
                    num(r.get("row_total")),
                    num(r.get("sec_total"))));
        }

        InspectorInsight.Result result = InspectorInsight.analyze(raws);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", start.toString());
        m.put("to", end.toString());
        // days 是含首含尾的天数：3 月 1 日到 3 月 31 日是 31 天，不是 30
        m.put("days", days);
        m.put("staff", result.rows().size());
        m.put("baselineNgRate", result.baselineNgRate());
        m.put("summary", result.summary());
        m.put("total", toMap(result.total()));
        List<Map<String, Object>> list = new ArrayList<>(result.rows().size());
        for (InspectorInsight.Row row : result.rows()) {
            list.add(toMap(row));
        }
        m.put("rows", list);
        return m;
    }

    private Map<String, Object> toMap(InspectorInsight.Row row) {
        InspectorInsight.Raw r = row.raw();
        Map<String, Object> m = new LinkedHashMap<>();
        // 合计行的 inspector 为 null，前端显示「合计」
        m.put("inspector", r.inspector());
        m.put("orders", r.orders());
        m.put("pieceTotal", r.pieceTotal());
        m.put("piecePass", r.piecePass());
        m.put("pieceFail", r.pieceFail());
        m.put("ngCount", r.ngCount());
        m.put("criticalNg", r.criticalNg());
        m.put("passOrders", r.passOrders());
        m.put("recheckOrders", r.recheckOrders());
        m.put("rowTotal", r.rowTotal());
        m.put("ngRate", row.ngRate());
        m.put("orderPassRate", row.orderPassRate());
        m.put("secPerRow", row.secPerRow());
        m.put("minutesPerOrder", row.minutesPerOrder());
        m.put("flags", row.flags());
        m.put("level", row.level());
        return m;
    }

    private LocalDate parse(String s, LocalDate fallback, String what) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(what + "格式应为 yyyy-MM-dd，例如 2026-10-01");
        }
    }

    /**
     * MyBatis 把 count/sum 映射进来可能是 Long、Integer、BigDecimal 或 Double
     * （{@code EXTRACT(EPOCH ...)} 在 PG 里是 numeric），直接强转会在驱动换实现的那天炸掉。
     */
    private static long num(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return new java.math.BigDecimal(v.toString().trim()).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
