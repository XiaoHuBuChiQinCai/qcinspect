package com.qc.inspect.service;

import com.qc.inspect.mapper.QcInspectionLineMapper;
import com.qc.inspect.report.CapabilityNarrative;
import com.qc.inspect.spc.CapabilityMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 月报里的「过程能力」一段。
 *
 * <p>做的事只有一件：把本月和上月的 Cpk 并排放到同一张表里，让「在往下走」这件事看得见。
 * 合格率掉下来的时候不良已经做出来了；Cpk 掉下来的时候还什么都没发生——这是月报里
 * 唯一一个能提前一个月的信号。
 *
 * <p>取数与算法全部复用 {@link CapabilityService#prepare} 和 {@link CapabilityMath}，
 * 这里不重算 σ̂：同一个特性在 SPC 页和月报上必须是同一套算法（窗口不同，但算法不能变）。
 */
@Service
@RequiredArgsConstructor
public class MonthlyCapabilityService {

    /** 与月报其它段一致：业务时区 +08:00，不跟着服务器和数据库的时区漂 */
    private static final ZoneOffset BIZ_OFFSET = ZoneOffset.ofHours(8);

    private final QcInspectionLineMapper lineMapper;
    private final CapabilityService capabilityService;

    /**
     * @param first     本月 1 号
     * @param nextFirst 下月 1 号（开区间）
     */
    @Transactional(readOnly = true)
    public Section build(LocalDate first, LocalDate nextFirst) {
        LocalDate last = nextFirst.minusDays(1);
        LocalDate prevFirst = first.minusMonths(1);

        // 门槛放到 2 个样本：样本不足的项也列出来并标灰，比干脆不显示好——
        // 「这项没评」和「这项没问题」在报表上长得一样，而前者要人去补抽检
        List<Map<String, Object>> items = lineMapper.monthlyCapabilityItems(
                first.atStartOfDay().atOffset(BIZ_OFFSET),
                nextFirst.atStartOfDay().atOffset(BIZ_OFFSET),
                2);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<CapabilityNarrative.Item> forNarrative = new ArrayList<>();
        int specChanged = 0;

        for (Map<String, Object> it : items) {
            Long productId = asLong(it.get("product_id"));
            Long itemId = asLong(it.get("item_id"));
            if (productId == null || itemId == null) {
                continue;
            }

            CapabilityService.Sample cur = capabilityService.prepare(productId, itemId, first, last);
            CapabilityMath.Result now = CapabilityMath.compute(
                    cur.values(), cur.meanMr(), cur.hasMoves(), cur.lsl(), cur.usl());
            if (now == null) {
                continue;
            }

            CapabilityService.Sample prev = capabilityService.prepare(productId, itemId, prevFirst, first.minusDays(1));
            CapabilityMath.Result before = CapabilityMath.compute(
                    prev.values(), prev.meanMr(), prev.hasMoves(), prev.lsl(), prev.usl());
            if (before != null && !sameSpec(cur, prev)) {
                // 两边分母不是同一道公差，比出来的差值说明不了任何事
                before = null;
                specChanged++;
            }

            String label = label(
                    Objects.toString(it.get("product_name"), null),
                    Objects.toString(it.get("item_name"), null),
                    Objects.toString(it.get("item_code"), null));

            boolean enough = now.n() >= CapabilityMath.MIN_SAMPLE;
            Double prevCpk = before == null ? null : round(before.cpk());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("productName", it.get("product_name"));
            row.put("itemName", it.get("item_name"));
            row.put("unit", it.get("unit"));
            row.put("n", now.n());
            row.put("prevN", before == null ? 0 : before.n());
            row.put("lsl", cur.lsl());
            row.put("usl", cur.usl());
            row.put("cpk", round(now.cpk()));
            row.put("prevCpk", prevCpk);
            // 差值只在两边都可比时给：一边是 3 个样本算出来的数，差多少都是噪声
            row.put("change", prevCpk == null || now.cpk() == null ? null : round(now.cpk() - prevCpk));
            row.put("outOfSpec", now.outOfSpec());
            row.put("verdict", now.verdict());
            row.put("verdictText", now.verdictText());
            row.put("enoughSamples", enough);
            rows.add(row);

            if (now.cpk() != null) {
                forNarrative.add(new CapabilityNarrative.Item(label, now.n(), round(now.cpk()),
                        prevCpk, before == null ? 0 : before.n()));
            }
        }

        // 最差的排最前：月报是给人挑毛病用的，不按字母序
        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> r.get("cpk") == null)
                .thenComparing(r -> r.get("cpk") == null ? 0.0 : (Double) r.get("cpk")));

        List<CapabilityNarrative.Item> usable = forNarrative.stream()
                .filter(i -> i.n() >= CapabilityMath.MIN_SAMPLE).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("hasData", !rows.isEmpty());
        data.put("judged", usable.size());
        data.put("insufficient", usable.stream().filter(i -> i.cpk() < 1.00).count());
        data.put("marginal", usable.stream().filter(i -> i.cpk() >= 1.00 && i.cpk() < 1.33).count());
        data.put("slipping", usable.stream().filter(CapabilityNarrative::isSlipping).count());
        data.put("fewSamples", forNarrative.size() - usable.size());
        data.put("specChanged", specChanged);
        data.put("minSample", CapabilityMath.MIN_SAMPLE);
        data.put("slipThreshold", CapabilityNarrative.SLIP);
        return new Section(data, CapabilityNarrative.build(forNarrative, specChanged));
    }

    /** 两个月算出来的规格限是否一致。一致才谈得上「比上月」 */
    private static boolean sameSpec(CapabilityService.Sample a, CapabilityService.Sample b) {
        return Objects.equals(a.lsl(), b.lsl()) && Objects.equals(a.usl(), b.usl());
    }

    private static String label(String productName, String itemName, String itemCode) {
        String item = itemName == null || itemName.isBlank() ? itemCode : itemName;
        String p = productName == null || productName.isBlank() ? "（未命名产品）" : productName;
        return item == null || item.isBlank() ? p : p + " · " + item;
    }

    private static Double round(Double v) {
        return v == null ? null : CapabilityMath.round(v, 2);
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number num ? num.longValue() : Long.valueOf(String.valueOf(o));
    }

    /** 一节的表格数据 + 结论文本。分开返回是因为结论要接在月报统一的 narrative 列表里 */
    public record Section(Map<String, Object> data, List<String> narrative) {
    }
}
