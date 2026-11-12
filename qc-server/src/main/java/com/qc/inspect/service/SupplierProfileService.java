package com.qc.inspect.service;

import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcSupplier;
import com.qc.inspect.mapper.QcSupplierMapper;
import com.qc.inspect.report.SupplierInsight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商画像。
 *
 * <p>存在的理由：主数据列表只有一列「件级合格率 94.2%」，采购拿着它去谈话，
 * 对方一句「那 5.8% 是哪几批、坏在哪」就把人问住了。更要紧的是——
 * <b>合格率相同的两家，问题性质可能完全相反</b>：一家是 12 批里 11 批零不良、
 * 1 批废掉 40%（偶发，查那一批就行），另一家是 12 批每批坏 5%（系统性，得改工艺）。
 * 两者都写着 95%，该做的事一件都不沾边。
 *
 * <p>所以这个接口不是「再算一遍合格率」，是把<b>批次分布</b>算出来，
 * 让「偶发」和「系统性」分开。判断集中在 {@link SupplierInsight}，这里只负责取数。
 */
@Service
@RequiredArgsConstructor
public class SupplierProfileService {

    private static final ZoneOffset BIZ_OFFSET = ZoneOffset.ofHours(8);
    private static final ZoneId BIZ_ZONE = ZoneId.of("+08:00");

    /** 默认看半年：三个月太短，看不出「以前是好的」；一年太长，去年的问题今年不一定要谈 */
    private static final int DEFAULT_MONTHS = 6;
    /** 上限两年。再往前，当时的采购和工艺多半都换人了，拿它谈话没有意义 */
    private static final int MAX_MONTHS = 24;

    /** 不良最多列 8 项，多了页面放不下，结论里也读不下去 */
    private static final int TOP_DEFECTS = 8;

    private final QcSupplierMapper supplierMapper;

    /**
     * @param months 看最近几个月（含本月），留空 6
     */
    @Transactional(readOnly = true)
    public Map<String, Object> profile(Long supplierId, Integer months) {
        if (supplierId == null) {
            throw BizException.of("供应商 id 不能为空");
        }
        QcSupplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null || (supplier.getDelFlag() != null && supplier.getDelFlag() != 0)) {
            throw BizException.of("供应商不存在：" + supplierId);
        }

        int m = months == null ? DEFAULT_MONTHS : months;
        if (m < 1) {
            m = 1;
        }
        if (m > MAX_MONTHS) {
            m = MAX_MONTHS;
        }

        LocalDate today = LocalDate.now(BIZ_ZONE);
        LocalDate first = YearMonth.from(today).minusMonths(m - 1).atDay(1);
        String from = first.atStartOfDay().atOffset(BIZ_OFFSET).toString();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", m);
        out.put("from", first.toString());
        out.put("to", today.toString());

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", supplier.getId());
        info.put("supplierCode", supplier.getSupplierCode());
        info.put("supplierName", supplier.getSupplierName());
        info.put("shortName", supplier.getShortName());
        info.put("category", supplier.getCategory());
        info.put("level", supplier.getLevel());
        info.put("status", supplier.getStatus());
        info.put("contact", supplier.getContact());
        info.put("phone", supplier.getPhone());
        out.put("supplier", info);

        // ---------- 汇总 ----------
        Map<String, Object> raw = supplierMapper.profileSummary(supplierId, from);
        long batches = num(raw, "batches");
        long lots = num(raw, "lots");
        long pieceTotal = num(raw, "piece_total");
        long piecePass = num(raw, "piece_pass");
        long perfectLots = num(raw, "perfect_lots");
        long badLots = num(raw, "bad_lots");
        Double worstRate = rate(raw == null ? null : raw.get("worst_batch_rate"));

        SupplierInsight.Profile p = new SupplierInsight.Profile(
                batches, lots, pieceTotal, piecePass, perfectLots, badLots, worstRate);
        SupplierInsight.Kind kind = SupplierInsight.kind(p);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("batches", batches);
        totals.put("lots", lots);
        totals.put("pieceTotal", pieceTotal);
        totals.put("piecePass", piecePass);
        totals.put("yieldRate", p.yieldRate());
        totals.put("perfectLots", perfectLots);
        totals.put("badLots", badLots);
        totals.put("badShare", p.badShare());
        totals.put("worstBatchRate", worstRate);
        out.put("totals", totals);
        out.put("kind", kind.name());
        out.put("kindLabel", kind.label());

        // ---------- 按月 ----------
        List<Map<String, Object>> monthly = new ArrayList<>();
        List<SupplierInsight.MonthPoint> points = new ArrayList<>();
        for (Map<String, Object> r : supplierMapper.profileMonthly(supplierId, from)) {
            long t = num(r, "piece_total");
            long pass = num(r, "piece_pass");
            String month = str(r.get("month"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month);
            row.put("batches", num(r, "batches"));
            row.put("lots", num(r, "lots"));
            row.put("pieceTotal", t);
            row.put("piecePass", pass);
            row.put("pieceFail", num(r, "piece_fail"));
            row.put("yieldRate", t > 0 ? (double) pass / t : null);
            monthly.add(row);
            points.add(new SupplierInsight.MonthPoint(month, t, pass));
        }
        out.put("monthly", monthly);

        // ---------- 不良 ----------
        List<Map<String, Object>> defectRows = supplierMapper.profileDefects(supplierId, from, TOP_DEFECTS);
        long defectQty = 0;
        for (Map<String, Object> r : defectRows) {
            defectQty += num(r, "qty");
        }
        List<Map<String, Object>> defects = new ArrayList<>();
        for (Map<String, Object> r : defectRows) {
            long qty = num(r, "qty");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("code", str(r.get("code")));
            d.put("name", str(r.get("name")));
            d.put("cnt", num(r, "cnt"));
            d.put("qty", qty);
            d.put("batches", num(r, "batches"));
            // 分母是这家供应商自己的不良件数，不是全厂：
            // 「你家的问题里外径占 56%」才有用，「占全厂 3%」说明不了任何事
            d.put("share", defectQty > 0 ? Math.round(qty * 1000.0 / defectQty) / 1000.0 : null);
            defects.add(d);
        }
        out.put("defects", defects);
        out.put("defectQty", defectQty);

        // ---------- 结论 ----------
        String topName = defects.isEmpty() ? null : str(defects.get(0).get("name"));
        Double topShare = defects.isEmpty() ? null
                : (defects.get(0).get("share") instanceof Number n ? n.doubleValue() : null);
        out.put("narrative", SupplierInsight.build(
                p, topName, topShare, SupplierInsight.trendNote(points)));

        return out;
    }

    private static long num(Map<String, Object> r, String key) {
        Object v = r == null ? null : r.get(key);
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

    /** 比率：驱动可能给 BigDecimal / Double，也可能给 null（没有合格率的单） */
    private static Double rate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
