package com.qc.inspect.service;

import com.qc.inspect.common.BizException;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcComplaintMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcGaugeCalibrationMapper;
import com.qc.inspect.mapper.QcGaugeMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.report.MonthlyNarrative;
import com.qc.inspect.report.MonthlyReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量月报。
 *
 * <p>这不是「又一个统计页」。工作台回答「今天有活没干完」，SPC 回答「这道工序稳不稳」，
 * 月报回答的是<b>「这个月质量该给谁发奖金、该让谁写整改」</b>——ISO 9001 管理评审
 * 每年要交的那张表，靠临时从五个页面抄数抄不出来。
 *
 * <p>几个刻意的口径选择：
 * <ul>
 *   <li><b>按月不按滚动窗口。</b>月报的月份必须能对齐财务月、对齐客户的对账周期，
 *       所以时间窗是 [月初 00:00, 下月初 00:00)，边界写死 +08:00，
 *       不依赖数据库时区和服务器的 now()。补跑一次结果和一月底跑的一致。</li>
 *   <li><b>只算 SUBMITTED / CLOSED。</b>草稿和作废单不代表任何检验结论，
 *       算进去会让合格率随「谁今天开了几张单没交」而波动。</li>
 *   <li><b>不剔除返工复检单。</b>复检单本身是质量成本的一部分，
 *       但它的合格率天然偏高（返工过的件），所以单独给一个 recheckOrders，
 *       让看的人自己决定要不要拆开看。</li>
 *   <li><b>量具是当前快照，不按月。</b>「现在有几把超期」不需要等月底。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    /** 业务时区固定 +08:00：服务器可能跑在 UTC（Docker 默认），不能让「月初」跟着漂 */
    private static final ZoneOffset BIZ_OFFSET = ZoneOffset.ofHours(8);
    private static final ZoneId BIZ_ZONE = ZoneId.of("+08:00");

    /** 主要不良最多列几项，多了页面放不下，结论里也读不下去 */
    private static final int TOP_DEFECTS = 8;
    /** 供应商只列最差的几个，好的不需要管 */
    private static final int WORST_SUPPLIERS = 5;

    private static final Map<String, String> TYPE_LABEL = Map.of(
            "IQC", "来料检验",
            "IPQC", "过程检验",
            "FQC", "最终检验",
            "OQC", "出货检验");

    private final QcInspectionMapper inspectionMapper;
    private final QcDefectMapper defectMapper;
    private final QcBatchMapper batchMapper;
    private final QcComplaintMapper complaintMapper;
    private final QcGaugeMapper gaugeMapper;
    private final QcGaugeCalibrationMapper calibrationMapper;

    /**
     * @param month yyyy-MM；留空取上月——月报基本都在月头上看，
     *              默认当月会是一屏「本月还没做完」的空数据
     */
    @Transactional(readOnly = true)
    public Map<String, Object> monthly(String month) {
        YearMonth ym = parseMonth(month);
        YearMonth prevYm = ym.minusMonths(1);

        LocalDate first = ym.atDay(1);
        LocalDate nextFirst = ym.plusMonths(1).atDay(1);
        LocalDate prevFirst = prevYm.atDay(1);

        Window cur = new Window(first, nextFirst);
        Window prev = new Window(prevFirst, first);

        // ---------- 本月与上月指标 ----------
        List<Map<String, Object>> curRows = inspectionMapper.monthlyByType(cur.fromTs(), cur.toTs());
        MonthlyReport curTotal = MonthlyReport.sum(toReports(curRows));
        MonthlyReport prevTotal = MonthlyReport.sum(toReports(
                inspectionMapper.monthlyByType(prev.fromTs(), prev.toTs())));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", ym.toString());
        m.put("label", ym.getYear() + " 年 " + ym.getMonthValue() + " 月");
        m.put("from", first.toString());
        m.put("to", ym.atEndOfMonth().toString());
        m.put("prevMonth", prevYm.toString());
        m.put("hasData", !curTotal.isEmpty());
        m.put("current", metrics(curTotal));
        m.put("previous", metrics(prevTotal));
        m.put("delta", delta(curTotal, prevTotal));

        // ---------- 按检验类型拆 ----------
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> row : curRows) {
            String type = str(row.get("inspect_type"));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("inspectType", type);
            t.put("inspectTypeName", TYPE_LABEL.getOrDefault(type, type == null ? "（未分类）" : type));
            t.putAll(metrics(toReport(row)));
            byType.add(t);
        }
        m.put("byType", byType);

        // ---------- 不良 ----------
        List<Map<String, Object>> topRows = defectMapper.monthlyTop(cur.fromTs(), cur.toTs());
        long defectQty = topRows.stream().mapToLong(r -> num(r.get("qty"))).sum();
        List<Map<String, Object>> defects = new ArrayList<>();
        for (int i = 0; i < topRows.size() && i < TOP_DEFECTS; i++) {
            Map<String, Object> r = topRows.get(i);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", str(r.get("name")));
            d.put("cnt", num(r.get("cnt")));
            d.put("qty", num(r.get("qty")));
            // 分母是本月的全部不良件数，不是全表——占比的分母错了，帕累托就没有意义
            d.put("share", defectQty <= 0 ? null
                    : Math.round(num(r.get("qty")) * 1000.0 / defectQty) / 1000.0);
            defects.add(d);
        }
        m.put("defects", defects);
        m.put("defectQty", defectQty);

        List<Map<String, Object>> dispositionRows = defectMapper.monthlyDisposition(cur.fromTs(), cur.toTs());
        List<Map<String, Object>> dispositions = new ArrayList<>();
        for (Map<String, Object> r : dispositionRows) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("disposition", str(r.get("disposition")));
            d.put("name", dispositionLabel(str(r.get("disposition"))));
            d.put("cnt", num(r.get("cnt")));
            d.put("qty", num(r.get("qty")));
            dispositions.add(d);
        }
        m.put("dispositions", dispositions);

        // ---------- 供应商 ----------
        List<Map<String, Object>> supRows = batchMapper.monthlySupplier(cur.fromTs(), cur.toTs());
        List<Map<String, Object>> suppliers = new ArrayList<>();
        for (int i = 0; i < supRows.size() && i < WORST_SUPPLIERS; i++) {
            Map<String, Object> r = supRows.get(i);
            long pieceTotal = num(r.get("piece_total"));
            long piecePass = num(r.get("piece_pass"));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("supplier", str(r.get("supplier")));
            s.put("batches", num(r.get("batches")));
            s.put("lots", num(r.get("lots")));
            s.put("pieceTotal", pieceTotal);
            s.put("piecePass", piecePass);
            s.put("yieldRate", MonthlyReport.ratio(piecePass, pieceTotal));
            suppliers.add(s);
        }
        m.put("suppliers", suppliers);

        // ---------- 客诉 ----------
        Map<String, Object> opened = complaintMapper.monthlyOpened(first.toString(), nextFirst.toString());
        Map<String, Object> all = complaintMapper.summary();
        Map<String, Object> complaints = new LinkedHashMap<>();
        long cOpened = num(opened == null ? null : opened.get("total"));
        long cHigh = num(opened == null ? null : opened.get("high"));
        long cClosed = complaintMapper.monthlyClosed(cur.fromTs(), cur.toTs());
        long cOpenNow = num(all == null ? null : all.get("open"));
        complaints.put("opened", cOpened);
        complaints.put("high", cHigh);
        complaints.put("closed", cClosed);
        complaints.put("openNow", cOpenNow);
        // 积压变化：本月新开 - 本月关闭 > 0 说明越积越多，这条比单纯看「未关闭数」有用
        complaints.put("backlogDelta", cOpened - cClosed);
        m.put("complaints", complaints);

        // ---------- 量具 ----------
        Map<String, Object> snap = gaugeMapper.snapshot();
        Map<String, Object> cal = calibrationMapper.countInPeriod(first.toString(), nextFirst.toString());
        Map<String, Object> gauges = new LinkedHashMap<>();
        gauges.put("total", num(snap == null ? null : snap.get("total")));
        gauges.put("active", num(snap == null ? null : snap.get("active")));
        gauges.put("suspended", num(snap == null ? null : snap.get("suspended")));
        gauges.put("scrapped", num(snap == null ? null : snap.get("scrapped")));
        gauges.put("overdue", num(snap == null ? null : snap.get("overdue")));
        Map<String, Object> calMap = new LinkedHashMap<>();
        calMap.put("total", num(cal == null ? null : cal.get("total")));
        calMap.put("pass", num(cal == null ? null : cal.get("pass_cnt")));
        calMap.put("fail", num(cal == null ? null : cal.get("fail_cnt")));
        calMap.put("limited", num(cal == null ? null : cal.get("limited_cnt")));
        gauges.put("calibrated", calMap);
        m.put("gauges", gauges);

        // ---------- 结论文本 ----------
        String topText = defects.stream().limit(3)
                .map(d -> (String) d.get("name"))
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        m.put("narrative", MonthlyNarrative.build(curTotal, prevTotal, m.get("label").toString(),
                topText, cOpened, cHigh, cClosed, cOpenNow,
                gauges.get("overdue") instanceof Number n1 ? n1.longValue() : 0,
                gauges.get("suspended") instanceof Number n2 ? n2.longValue() : 0));

        return m;
    }

    // ==================== 内部 ====================

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(BIZ_ZONE).minusMonths(1);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            // 不能吐 Spring 的英文异常，现场看到「DateTimeParseException」只会来问人
            throw BizException.of("月份格式应为 yyyy-MM，例如 2026-10");
        }
    }

    private Map<String, Object> metrics(MonthlyReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orders", r.orders());
        m.put("passOrders", r.passOrders());
        m.put("pieceTotal", r.pieceTotal());
        m.put("piecePass", r.piecePass());
        m.put("pieceFail", r.pieceFail());
        m.put("recheckOrders", r.recheckOrders());
        m.put("yieldRate", r.yieldRate());
        m.put("orderYieldRate", r.orderYieldRate());
        m.put("recheckRate", r.recheckRate());
        m.put("ppm", r.ppm());
        return m;
    }

    private Map<String, Object> delta(MonthlyReport cur, MonthlyReport prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yieldPp", cur.yieldDeltaPp(prev));
        m.put("orderYieldPp", cur.orderYieldDeltaPp(prev));
        m.put("orders", prev.isEmpty() ? null : cur.orders() - prev.orders());
        m.put("pieceTotal", prev.isEmpty() ? null : cur.pieceTotal() - prev.pieceTotal());
        return m;
    }

    private List<MonthlyReport> toReports(List<Map<String, Object>> rows) {
        List<MonthlyReport> list = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            list.add(toReport(r));
        }
        return list;
    }

    private MonthlyReport toReport(Map<String, Object> r) {
        return new MonthlyReport(
                num(r.get("orders")),
                num(r.get("pass_orders")),
                num(r.get("piece_total")),
                num(r.get("piece_pass")),
                num(r.get("piece_fail")),
                num(r.get("recheck_orders")));
    }

    /**
     * MyBatis 把 count/sum 映射进来可能是 Long、Integer 或 BigDecimal，
     * 直接强转会在「数据量超过 int」或者驱动换了聚合实现的那天炸掉。
     */
    private static long num(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String dispositionLabel(String d) {
        if (d == null) {
            return "未处置";
        }
        return switch (d) {
            case "REWORK" -> "返工";
            case "SCRAP" -> "报废";
            case "CONCESSION" -> "让步接收";
            case "RETURN" -> "退货";
            default -> d;
        };
    }

    /** 一个月份的时间窗。带 +08:00 传给 SQL，和数据库、和服务器时区都解耦 */
    private record Window(LocalDate from, LocalDate to) {
        String fromTs() {
            return from.atStartOfDay().atOffset(BIZ_OFFSET).toString();
        }

        String toTs() {
            return to.atStartOfDay().atOffset(BIZ_OFFSET).toString();
        }
    }
}
