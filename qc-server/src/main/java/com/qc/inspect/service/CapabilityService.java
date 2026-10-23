package com.qc.inspect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.mapper.QcInspectionLineMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 过程能力分析（SPC）。
 *
 * <p>系统的判定层只回答「这批收不收」，回答不了「这道工序稳不稳」。同样 100 件全过，
 * 数据贴着规格限边缘走和稳稳压在中线，后续批次的翻车概率差一个数量级——
 * 而这正是 Cp/Cpk 要量化的东西。
 *
 * <h3>为什么 Cp/Cpk 和 Pp/Ppk 分开算</h3>
 * <p>Cp/Cpk 用的是<strong>组内变异</strong> σ̂（由移动极差 MR̄ ÷ d2 = MR̄ ÷ 1.128 估计），
 * 只看同一张检验单内部相邻测量的抖动，把「批与批之间的漂移」排除在外；
 * Pp/Ppk 用<strong>整体标准差</strong> s，把全部变异都算进去。
 * 所以人机料法都没变、只是批次之间有漂移时，会出现 Cp 好看而 Pp 难看的组合——
 * 那不是算错，那是在提示「工序能力够，但没控住」。
 *
 * <h3>为什么不剔除超规值</h3>
 * <p>把超规点剔掉再算 Cpk，等于自己给自己发合格证。过程能力分析的全部意义就是
 * 把「过程在往外飘」这件事摆到台面上，所以超规值一律计入，并单独报个数。
 */
@Service
@RequiredArgsConstructor
public class CapabilityService {

    /** 移动极差法的 d2 常数：子组容量 n=2 时 d2 = 1.128 */
    private static final double D2 = 1.128;
    /** 单值控制图上下限系数 E2 = 3 / d2 ≈ 2.66 */
    private static final double E2 = 3.0 / D2;
    /** 移动极差图上限系数 D4（n=2） = 3.267 */
    private static final double D4 = 3.267;
    /** 样本量下限。低于这个数算出来的 Cpk 是噪声，不是结论 */
    private static final int MIN_SAMPLE = 25;

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
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate begin = from == null ? end.minusDays(90) : from;
        if (begin.isAfter(end)) {
            throw BizException.of("开始日期不能晚于结束日期");
        }

        List<Map<String, Object>> rows = lineMapper.capabilitySamples(
                productId, itemId, begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        if (rows.isEmpty()) {
            throw BizException.of("该区间内没有可分析的实测值。过程能力只统计已提交/已归档的单据，"
                    + "草稿和作废单不计入");
        }

        // ---- 1. 摊平实测值，同时保留「属于哪张单」用来算组内移动极差 ----
        List<Double> all = new ArrayList<>();
        List<Moves> moves = new ArrayList<>();       // 每条移动极差：值 + 起点信息
        Map<Long, Double> prevInInspection = new LinkedHashMap<>();
        Set<String> specPairs = new LinkedHashSet<>();
        BigDecimal lastMin = null;
        BigDecimal lastMax = null;
        String itemCode = null;
        String itemName = null;
        String unit = null;

        for (Map<String, Object> row : rows) {
            List<Double> values = parseValues(asString(row.get("measured_values")));
            if (values.isEmpty()) {
                continue;
            }
            Long insId = asLong(row.get("inspection_id"));
            Integer pieceNo = asInt(row.get("piece_no"));
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

            for (int i = 0; i < values.size(); i++) {
                double v = values.get(i);
                all.add(v);
                Double prev = prevInInspection.get(insId);
                if (prev != null) {
                    moves.add(new Moves(Math.abs(v - prev), insId, pieceNo, i + 1, v));
                }
                prevInInspection.put(insId, v);
            }
        }

        if (all.size() < 2) {
            throw BizException.of("实测值不足 2 个，算不出标准差。至少要有 2 个测量值才能做过程能力分析");
        }

        // ---- 2. 基础统计量 ----
        int n = all.size();
        double sum = 0;
        for (double v : all) {
            sum += v;
        }
        double mean = sum / n;

        double ss = 0;
        for (double v : all) {
            ss += (v - mean) * (v - mean);
        }
        double sOverall = Math.sqrt(ss / (n - 1));

        double meanMr = 0;
        for (Moves m : moves) {
            meanMr += m.value();
        }
        meanMr = moves.isEmpty() ? 0 : meanMr / moves.size();
        // 一张单只测了一个点时没有移动极差，退回用整体标准差估 σ̂，并在提示里说清楚
        boolean mrFallback = moves.isEmpty();
        double sigmaWithin = mrFallback ? sOverall : meanMr / D2;

        Double lsl = lastMin == null ? null : lastMin.doubleValue();
        Double usl = lastMax == null ? null : lastMax.doubleValue();

        // ---- 3. 能力指数 ----
        Double cp = (lsl == null || usl == null) ? null : ratio(usl - lsl, 6 * sigmaWithin);
        Double pp = (lsl == null || usl == null) ? null : ratio(usl - lsl, 6 * sOverall);

        double lo = lsl == null ? Double.NEGATIVE_INFINITY : mean - lsl;
        double hi = usl == null ? Double.NEGATIVE_INFINITY : usl - mean;
        double half = Math.min(lo, hi);   // 只有单边规格时，另一边是 -∞，min 自然取到有边的那侧
        Double cpk = half == Double.NEGATIVE_INFINITY ? null : ratio(half, 3 * sigmaWithin);
        Double ppk = half == Double.NEGATIVE_INFINITY ? null : ratio(half, 3 * sOverall);

        // ---- 4. 超规与直方图 ----
        int outOfSpec = 0;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (double v : all) {
            if ((lsl != null && v < lsl) || (usl != null && v > usl)) {
                outOfSpec++;
            }
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("productName", asString(rows.get(0).get("product_name")));
        result.put("itemId", itemId);
        result.put("itemCode", itemCode);
        result.put("itemName", itemName);
        result.put("unit", unit);
        result.put("from", begin.toString());
        result.put("to", end.toString());
        result.put("inspectionCount", prevInInspection.size());

        result.put("n", n);
        result.put("lsl", lsl);
        result.put("usl", usl);
        result.put("mean", r(mean, 4));
        result.put("stdOverall", r(sOverall, 6));
        result.put("stdWithin", r(sigmaWithin, 6));
        result.put("meanMr", r(meanMr, 6));
        result.put("dataMin", r(minV, 4));
        result.put("dataMax", r(maxV, 4));

        result.put("cp", r(cp));
        result.put("cpk", r(cpk));
        result.put("pp", r(pp));
        result.put("ppk", r(ppk));

        result.put("outOfSpec", outOfSpec);
        result.put("outOfSpecRate", r(outOfSpec * 100.0 / n, 2));

        // ---- 5. 判定 ----
        Double judgeOn = cpk != null ? cpk : ppk;
        String verdict;
        String verdictText;
        if (judgeOn == null) {
            verdict = "UNKNOWN";
            verdictText = "无法判定";
        } else if (judgeOn >= 1.67) {
            verdict = "EXCELLENT";
            verdictText = "过程能力优秀";
        } else if (judgeOn >= 1.33) {
            verdict = "SUFFICIENT";
            verdictText = "过程能力充足";
        } else if (judgeOn >= 1.00) {
            verdict = "MARGINAL";
            verdictText = "过程能力勉强";
        } else {
            verdict = "INSUFFICIENT";
            verdictText = "过程能力不足";
        }
        result.put("verdict", verdict);
        result.put("verdictText", verdictText);
        result.put("notes", notes(n, lsl, usl, specPairs.size() > 1, mrFallback, outOfSpec, verdict));

        // ---- 6. 直方图与 I-MR 控制图 ----
        result.put("histogram", histogram(all, minV, maxV, lsl, usl, mean));
        result.put("controlChart", controlChart(all, moves, mean, meanMr, mrFallback));

        return result;
    }

    /**
     * 提示信息。这些都是「看数的人必须知道、否则会误读」的前提，
     * 放在页面上比写在文档里管用。
     */
    private List<String> notes(int n, Double lsl, Double usl, boolean specChanged,
                               boolean mrFallback, int outOfSpec, String verdict) {
        List<String> list = new ArrayList<>();
        if (lsl == null && usl == null) {
            list.add("该检验项没有设规格上下限，算不出 Cp/Cpk。要评能力得先有「多少算合格」的边界");
        } else if (lsl == null || usl == null) {
            list.add("只有单边规格，Cp/Pp 无定义，只看 Cpk/Ppk");
        }
        if (n < MIN_SAMPLE) {
            list.add("样本量 " + n + "，不足 " + MIN_SAMPLE + " 个。样本太少时标准差本身抖得厉害，"
                    + "这时的 Cpk 只能当个大概，别拿去和客户对账");
        }
        if (specChanged) {
            list.add("统计区间内规格限变过，本次按最后一次的规格计算。要严格对比，请缩短区间到规格未变的时段");
        }
        if (mrFallback) {
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
        h.put("mean", r(mean, 4));
        return h;
    }

    /**
     * 单值-移动极差（I-MR）控制图。
     *
     * <p>I 图看「有没有单点失控」，MR 图看「波动有没有突然变大」。两张一起看才靠谱：
     * 只看 I 图，一个均值不变但突然抖起来的工序会显得完全正常。
     */
    private Map<String, Object> controlChart(List<Double> values, List<Moves> moves,
                                             double mean, double meanMr, boolean mrFallback) {
        Map<String, Object> c = new LinkedHashMap<>();
        List<Map<String, Object>> singles = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("seq", i + 1);
            p.put("value", r(values.get(i), 4));
            singles.add(p);
        }
        double iUcl = mean + E2 * meanMr;
        double iLcl = mean - E2 * meanMr;

        Map<String, Object> iChart = new LinkedHashMap<>();
        iChart.put("points", singles);
        iChart.put("center", r(mean, 4));
        iChart.put("ucl", r(iUcl, 4));
        iChart.put("lcl", r(iLcl, 4));
        iChart.put("note", "单值图：看均值有没有失控");

        // 移动极差点必须来自组内相邻测量，不能拿摊平后的序列重算——
        // 那样会把「跨单跳变」也算成一格波动，MR 图整张失真
        List<Map<String, Object>> mrs = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("seq", i + 1);
            p.put("value", r(moves.get(i).value(), 4));
            mrs.add(p);
        }
        Map<String, Object> mrChart = new LinkedHashMap<>();
        mrChart.put("points", mrFallback ? List.of() : mrs);
        mrChart.put("center", r(meanMr, 4));
        mrChart.put("ucl", r(D4 * meanMr, 4));
        mrChart.put("lcl", 0.0);
        mrChart.put("note", mrFallback
                ? "缺少移动极差数据"
                : "移动极差图：看波动有没有突然变大");

        c.put("individual", iChart);
        c.put("movingRange", mrChart);
        return c;
    }

    private Map<String, Object> bin(double from, double to, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r(from, 4));
        m.put("to", r(to, 4));
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

    /** 分母为 0 或负（说明数据几乎没有波动）时不给数，别返回 Infinity 让前端画出个 NULL */
    private Double ratio(double numerator, double denominator) {
        if (denominator <= 0 || Double.isNaN(denominator) || Double.isInfinite(denominator)) {
            return null;
        }
        return numerator / denominator;
    }

    private Double r(Double v) {
        return v == null ? null : r(v, 3);
    }

    private double r(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
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

    /** 一条移动极差及其上下文，出问题时能说清是哪张单哪一件 */
    private record Moves(double value, Long inspectionId, Integer pieceNo, int pointNo, double to) {
        Moves {
            Objects.requireNonNull(inspectionId);
        }
    }
}
