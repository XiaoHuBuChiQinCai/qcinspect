package com.qc.inspect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.mapper.QcInspectionLineMapper;
import com.qc.inspect.spc.CapabilityMath;
import com.qc.inspect.spc.ControlRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 过程能力分析（SPC）。
 *
 * <p>系统的判定层只回答「这批收不收」，回答不了「这道工序稳不稳」。同样 100 件全过，
 * 数据贴着规格限边缘走和稳稳压在中线，后续批次的翻车概率差一个数量级——
 * 而这正是 Cp/Cpk 要量化的东西。
 *
 * <p>统计与判档本身在 {@link CapabilityMath}——月报的月度能力用的是同一份，
 * 否则同一个特性在 SPC 页和月报上会算出两个 Cpk，然后没人解释得清差在哪。
 * 这里只管取数、拼直方图与控制图、以及给人看的提示。
 */
@Service
@RequiredArgsConstructor
public class CapabilityService {

    /** 业务时区固定 +08:00：容器默认 UTC，「今天」在早 8 点前会取到昨天 */
    private static final ZoneOffset BIZ_OFFSET = ZoneOffset.ofHours(8);
    private static final ZoneId BIZ_ZONE = ZoneId.of("+08:00");

    private final QcInspectionLineMapper lineMapper;
    private final ObjectMapper objectMapper;

    /** 可分析的「产品 + 检验项」清单 */
    public List<Map<String, Object>> items() {
        List<Map<String, Object>> out = new ArrayList<>();
        // 原生 SQL 的列名带下划线，直接吐给前端会和其它接口的驼峰风格打架，这里统一转一次
        for (Map<String, Object> r : lineMapper.capabilityItems()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", asLong(r.get("product_id")));
            m.put("productName", asString(r.get("product_name")));
            m.put("itemId", asLong(r.get("item_id")));
            m.put("itemCode", asString(r.get("item_code")));
            m.put("itemName", asString(r.get("item_name")));
            m.put("unit", asString(r.get("unit")));
            m.put("inspectionCount", asInt(r.get("inspection_cnt")));
            m.put("sampleCount", asInt(r.get("sample_cnt")));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> analyze(Long productId, Long itemId, LocalDate from, LocalDate to) {
        if (productId == null || itemId == null) {
            throw BizException.of("请选择要分析的产品和检验项");
        }
        LocalDate end = to == null ? LocalDate.now(BIZ_ZONE) : to;
        LocalDate begin = from == null ? end.minusDays(90) : from;
        if (begin.isAfter(end)) {
            throw BizException.of("开始日期不能晚于结束日期");
        }

        Sample sp = prepare(productId, itemId, begin, end);
        if (sp.inspectionCount() == 0) {
            throw BizException.of("该区间内没有可分析的实测值。过程能力只统计已提交/已归档的单据，"
                    + "草稿和作废单不计入");
        }
        if (sp.values().size() < 2) {
            throw BizException.of("实测值不足 2 个，算不出标准差。至少要有 2 个测量值才能做过程能力分析");
        }

        List<Double> values = sp.values();
        CapabilityMath.Result calc = CapabilityMath.compute(
                values, sp.meanMr(), sp.hasMoves(), sp.lsl(), sp.usl());
        if (calc == null) {
            // 上面已经拦过 size<2，走到这里说明 CapabilityMath 的入参契约变了
            throw BizException.of("实测值不足 2 个，算不出标准差");
        }

        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (double v : values) {
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("productName", sp.productName());
        result.put("itemId", itemId);
        result.put("itemCode", sp.itemCode());
        result.put("itemName", sp.itemName());
        result.put("unit", sp.unit());
        result.put("from", begin.toString());
        result.put("to", end.toString());
        result.put("inspectionCount", sp.inspectionCount());

        result.put("n", calc.n());
        result.put("lsl", sp.lsl());
        result.put("usl", sp.usl());
        result.put("mean", CapabilityMath.round(calc.mean(), 4));
        result.put("stdOverall", CapabilityMath.round(calc.sigmaOverall(), 6));
        result.put("stdWithin", CapabilityMath.round(calc.sigmaWithin(), 6));
        result.put("meanMr", CapabilityMath.round(sp.meanMr(), 6));
        result.put("dataMin", CapabilityMath.round(minV, 4));
        result.put("dataMax", CapabilityMath.round(maxV, 4));

        result.put("cp", r(calc.cp()));
        result.put("cpk", r(calc.cpk()));
        result.put("pp", r(calc.pp()));
        result.put("ppk", r(calc.ppk()));

        result.put("outOfSpec", calc.outOfSpec());
        result.put("outOfSpecRate", CapabilityMath.round(calc.outOfSpec() * 100.0 / calc.n(), 2));

        result.put("verdict", calc.verdict());
        result.put("verdictText", calc.verdictText());

        // ---- 直方图与 I-MR 控制图 ----
        result.put("histogram", histogram(values, minV, maxV, sp.lsl(), sp.usl(), calc.mean()));

        Map<String, Object> chart = controlChart(values, sp.mrValues(), calc.mean(),
                sp.meanMr(), !sp.hasMoves());
        /*
         * 判异。控制图只画不判等于没画：一条连续 9 点偏在中心线上侧的曲线，
         * 看上去和正常曲线一样，只有真超出控制限才有人注意到——那时候批量不良已经做出来了。
         * σ̂ 与画控制限用的是同一个（meanMr/D2），否则会出现「点在限外却说无异常」。
         */
        ControlRules.Report rules = ControlRules.check(values, calc.mean(), calc.sigmaWithin());
        List<Map<String, Object>> alarms = new ArrayList<>();
        for (ControlRules.Alarm a : rules.alarms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.code());
            m.put("level", a.level());
            m.put("title", a.title());
            m.put("text", a.text());
            m.put("fromSeq", a.fromSeq());
            m.put("toSeq", a.toSeq());
            m.put("hits", a.hits());
            alarms.add(m);
        }
        chart.put("alarms", alarms);
        chart.put("verdict", rules.verdict());
        chart.put("verdictText", rules.verdictText());
        result.put("controlChart", chart);

        List<String> noteList = notes(calc.n(), sp.lsl(), sp.usl(), sp.specChanged(),
                !sp.hasMoves(), calc.outOfSpec(), calc.verdict());
        if (!alarms.isEmpty()) {
            noteList.add("控制图判出 " + alarms.size() + " 类异常排列。这些点不一定超规格，"
                    + "但过程已经不在统计掌控内：先按标红的位置回去查那几张单，再决定要不要停线");
        }
        result.put("notes", noteList);

        return result;
    }

    /**
     * 取区间内的样本，并把「同一条检验单内部相邻测量」的移动极差一并算好。
     *
     * <p>月报的月度能力也走这里：窗口不同（那边是一个自然月），但「怎么取数、怎么摊平、
     * 怎么算移动极差、规格按哪一次快照」必须只有一套实现。
     *
     * @param from 闭区间起点（含）
     * @param to   闭区间终点（含）；内部按 +08:00 扩到次日零点
     */
    public Sample prepare(Long productId, Long itemId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = lineMapper.capabilitySamples(
                productId, itemId,
                from.atStartOfDay().atOffset(BIZ_OFFSET),
                to.plusDays(1).atStartOfDay().atOffset(BIZ_OFFSET));

        List<Double> values = new ArrayList<>();
        List<Double> mrValues = new ArrayList<>();
        Map<Long, Double> prevInInspection = new LinkedHashMap<>();
        Set<String> specPairs = new LinkedHashSet<>();
        BigDecimal lastMin = null;
        BigDecimal lastMax = null;
        String productName = null;
        String itemCode = null;
        String itemName = null;
        String unit = null;

        for (Map<String, Object> row : rows) {
            List<Double> one = parseValues(asString(row.get("measured_values")));
            if (one.isEmpty()) {
                continue;
            }
            Long insId = asLong(row.get("inspection_id"));
            productName = productName == null ? asString(row.get("product_name")) : productName;
            itemCode = itemCode == null ? asString(row.get("item_code")) : itemCode;
            itemName = itemName == null ? asString(row.get("item_name")) : itemName;
            unit = unit == null ? asString(row.get("unit")) : unit;

            BigDecimal mn = asDecimal(row.get("min_value"));
            BigDecimal mx = asDecimal(row.get("max_value"));
            // 规格按「最后一次测量时的快照」为准：行是按检验单 id 升序来的，最后一行最新
            if (mn != null || mx != null) {
                specPairs.add((mn == null ? "" : mn.toPlainString()) + "|" + (mx == null ? "" : mx.toPlainString()));
                lastMin = mn;
                lastMax = mx;
            }

            // 移动极差只在同一张单内部算：跨单相减得到的是「两批之间的漂移」，
            // 混进 σ̂ 会把组内变异算大、Cpk 算小
            for (double v : one) {
                Double prev = prevInInspection.get(insId);
                if (prev != null) {
                    mrValues.add(Math.abs(v - prev));
                }
                prevInInspection.put(insId, v);
                values.add(v);
            }
        }

        double meanMr = 0;
        for (double mr : mrValues) {
            meanMr += mr;
        }
        meanMr = mrValues.isEmpty() ? 0 : meanMr / mrValues.size();

        return new Sample(values, mrValues, meanMr, !mrValues.isEmpty(),
                lastMin == null ? null : lastMin.doubleValue(),
                lastMax == null ? null : lastMax.doubleValue(),
                prevInInspection.size(), specPairs.size() > 1,
                productName, itemCode, itemName, unit);
    }

    /**
     * 一次取数的结果。
     *
     * <p>{@code mrValues} 与 {@code values} 不是同一个序列（前者按组内相邻算，长度通常是后者的
     * 一部分），别拿 {@code values} 重算移动极差——那会把跨单跳变也算成一格波动。
     */
    public record Sample(List<Double> values, List<Double> mrValues, double meanMr, boolean hasMoves,
                         Double lsl, Double usl, int inspectionCount, boolean specChanged,
                         String productName, String itemCode, String itemName, String unit) {
    }

    /**
     * 提示信息。这些都是「看数的人必须知道、否则会误读」的前提，
     * 放在页面上比写在文档里管用。
     */
    private List<String> notes(int n, Double lsl, Double usl, boolean specChanged,
                               boolean hasMoves, int outOfSpec, String verdict) {
        List<String> list = new ArrayList<>();
        if (lsl == null && usl == null) {
            list.add("该检验项没有设规格上下限，算不出 Cp/Cpk。要评能力得先有「多少算合格」的边界");
        } else if (lsl == null || usl == null) {
            list.add("只有单边规格，Cp/Pp 无定义，只看 Cpk/Ppk");
        }
        if (n < CapabilityMath.MIN_SAMPLE) {
            list.add("样本量 " + n + "，不足 " + CapabilityMath.MIN_SAMPLE + " 个。样本太少时标准差本身抖得厉害，"
                    + "这时的 Cpk 只能当个大概，别拿去和客户对账");
        }
        if (specChanged) {
            list.add("统计区间内规格限变过，本次按最后一次的规格计算。要严格对比，请缩短区间到规格未变的时段");
        }
        if (!hasMoves) {
            list.add("每张检验单只有一个测量值，算不出移动极差，σ̂ 退回用整体标准差估计，"
                    + "此时 Cp 与 Pp 会相等");
        }
        if (outOfSpec > 0) {
            list.add("有 " + outOfSpec + " 个实测值超出规格（已计入统计，未剔除）。超规值被剔掉的话，"
                    + "Cpk 会虚高");
        }
        if ("INSUFFICIENT".equals(verdict)) {
            list.add("Cpk < 1.00，按 GB/T 4091 属于过程能力不足，正常生产下不良率会明显超出 AQL 允许水平");
        } else if ("MARGINAL".equals(verdict)) {
            list.add("Cpk 在 1.00~1.33 之间，过程能力勉强，一旦有漂移就会出批不合格");
        }
        return list;
    }

    private Map<String, Object> histogram(List<Double> values, double minV, double maxV,
                                          Double lsl, Double usl, double mean) {
        Map<String, Object> h = new LinkedHashMap<>();
        int k = (int) Math.ceil(1 + Math.log(values.size()) / Math.log(2));  // Sturges 公式
        k = Math.max(5, Math.min(k, 20));
        double span = maxV - minV;
        List<Map<String, Object>> bins = new ArrayList<>();
        if (span <= 0) {
            bins.add(bin(minV, minV, values.size()));
        } else {
            // 分箱边界往外让半个箱宽，避免最大值落在最后一个区间的右边界外
            double width = span / k;
            double start = minV - width * 0.5;
            int[] counts = new int[k];
            for (double v : values) {
                int idx = (int) Math.floor((v - start) / width);
                idx = Math.max(0, Math.min(idx, k - 1));
                counts[idx]++;
            }
            for (int i = 0; i < k; i++) {
                bins.add(bin(start + i * width, start + (i + 1) * width, counts[i]));
            }
        }
        h.put("bins", bins);
        h.put("lsl", lsl);
        h.put("usl", usl);
        h.put("mean", CapabilityMath.round(mean, 4));
        return h;
    }

    /**
     * 单值-移动极差（I-MR）控制图。
     *
     * <p>I 图看「有没有单点失控」，MR 图看「波动有没有突然变大」。两张一起看才靠谱：
     * 只看 I 图，一个均值不变但突然抖起来的工序会显得完全正常。
     */
    private Map<String, Object> controlChart(List<Double> values, List<Double> mrValues,
                                             double mean, double meanMr, boolean noMoves) {
        Map<String, Object> c = new LinkedHashMap<>();
        List<Map<String, Object>> singles = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("seq", i + 1);
            p.put("value", CapabilityMath.round(values.get(i), 4));
            singles.add(p);
        }
        double iUcl = mean + CapabilityMath.E2 * meanMr;
        double iLcl = mean - CapabilityMath.E2 * meanMr;

        Map<String, Object> iChart = new LinkedHashMap<>();
        iChart.put("points", singles);
        iChart.put("center", CapabilityMath.round(mean, 4));
        iChart.put("ucl", CapabilityMath.round(iUcl, 4));
        iChart.put("lcl", CapabilityMath.round(iLcl, 4));
        iChart.put("note", "单值图：看均值有没有失控");

        List<Map<String, Object>> mrs = new ArrayList<>();
        for (int i = 0; i < mrValues.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("seq", i + 1);
            p.put("value", CapabilityMath.round(mrValues.get(i), 4));
            mrs.add(p);
        }
        Map<String, Object> mrChart = new LinkedHashMap<>();
        mrChart.put("points", noMoves ? List.of() : mrs);
        mrChart.put("center", CapabilityMath.round(meanMr, 4));
        mrChart.put("ucl", CapabilityMath.round(CapabilityMath.D4 * meanMr, 4));
        mrChart.put("lcl", 0.0);
        mrChart.put("note", noMoves
                ? "缺少移动极差数据"
                : "移动极差图：看波动有没有突然变大");

        c.put("individual", iChart);
        c.put("movingRange", mrChart);
        return c;
    }

    private Map<String, Object> bin(double from, double to, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", CapabilityMath.round(from, 4));
        m.put("to", CapabilityMath.round(to, 4));
        m.put("count", count);
        return m;
    }

    /** 实测值存的是 JSON 数组字符串，元素可能是 "12.03" 这样的字符串，也可能是数字 */
    private List<Double> parseValues(String raw) {
        List<Double> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isArray()) {
                return list;
            }
            for (JsonNode e : node) {
                String t = e.isNumber() ? String.valueOf(e.asDouble()) : e.asText(null);
                if (t == null || t.isBlank()) {
                    continue;
                }
                try {
                    list.add(Double.parseDouble(t.trim()));
                } catch (NumberFormatException ignored) {
                    // 单点脏数据不该让整份分析挂掉，跳过即可（计数由调用方统计）
                }
            }
        } catch (Exception ignored) {
            // 同上：解析失败的整行跳过
        }
        return list;
    }

    private Double r(Double v) {
        return v == null ? null : CapabilityMath.round(v, 3);
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number num ? num.longValue() : Long.valueOf(String.valueOf(o));
    }

    private Integer asInt(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number num ? num.intValue() : Integer.valueOf(String.valueOf(o));
    }

    private BigDecimal asDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
