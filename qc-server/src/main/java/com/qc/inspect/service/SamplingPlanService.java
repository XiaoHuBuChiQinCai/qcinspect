package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcSamplingPlan;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.mapper.QcSamplingPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽样方案：把「批量」翻译成「抽几件、允收几件」。
 *
 * <p>两种模式并存：
 * <ul>
 *   <li>FIXED — 标准上写死样本量与 Ac。老产品、单件小批、客户另有约定时用这个。</li>
 *   <li>AQL   — 按 GB/T 2828.1 查表。批量越大抽得越多，Ac 跟着往上走。</li>
 * </ul>
 *
 * <p>为什么 AQL 模式下不接受人工指定样本量：抽样量一旦能被随手改，
 * 「按国标抽检」这句话就没有证据价值了。要改就改标准，改标准会留版本痕迹。
 */
@Service
@RequiredArgsConstructor
public class SamplingPlanService {

    public static final String MODE_FIXED = "FIXED";
    public static final String MODE_AQL = "AQL";
    /** 内置方案组：GB/T 2828.1-2012 正常检验一次抽样 */
    public static final String DEFAULT_PLAN_CODE = "GB2828-1";
    public static final String DEFAULT_LEVEL = "II";
    public static final BigDecimal DEFAULT_AQL = new BigDecimal("2.500");

    private final QcSamplingPlanMapper planMapper;

    // ==================== 对外入口 ====================

    /**
     * 算出这张单该抽几件、允收几件。
     *
     * @param standard        生效中的标准
     * @param batchQty        批次数量（批量 N），空或非正数按 1 处理
     * @param manualSampleQty 前端指定样本量，仅 FIXED 模式生效
     */
    @Transactional(readOnly = true)
    public SamplingResult resolve(QcStandard standard, Integer batchQty, Integer manualSampleQty) {
        int lotQty = batchQty == null || batchQty <= 0 ? 1 : batchQty;
        String samplingType = standard.getSamplingType() == null ? "FULL" : standard.getSamplingType();

        if (MODE_AQL.equals(standard.getSamplingPlan())) {
            return resolveByAql(standard, lotQty);
        }
        return resolveFixed(standard, samplingType, lotQty, manualSampleQty);
    }

    /** 方案表里有哪几档 AQL / 检验水平，配置页的下拉框据此生成，不写死在前端 */
    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        List<QcSamplingPlan> all = planMapper.selectList(
                new LambdaQueryWrapper<QcSamplingPlan>().orderByAsc(QcSamplingPlan::getLotSizeMin));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planCodes", all.stream().map(QcSamplingPlan::getPlanCode).distinct().sorted().toList());
        m.put("levels", all.stream().map(QcSamplingPlan::getInspectionLevel).distinct().sorted().toList());
        m.put("aqls", all.stream().map(QcSamplingPlan::getAql).distinct().sorted().toList());
        return m;
    }

    /** 方案表全量行，PC 端配置页用来渲染「批量区间 → 字码 → n → Ac」对照表 */
    @Transactional(readOnly = true)
    public List<QcSamplingPlan> list(BigDecimal aql, String level) {
        return planMapper.selectList(new LambdaQueryWrapper<QcSamplingPlan>()
                .eq(QcSamplingPlan::getInspectionLevel, normalizeLevel(level))
                .eq(QcSamplingPlan::getAql, normalizeAql(aql))
                .orderByAsc(QcSamplingPlan::getLotSizeMin));
    }

    /** 方案表 + 某个试算批量的命中结果，一次返回给配置页做预览 */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(BigDecimal aql, String level, Integer lotQty) {
        BigDecimal a = normalizeAql(aql);
        String l = normalizeLevel(level);
        List<QcSamplingPlan> rows = list(a, l);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aql", a);
        m.put("inspectionLevel", l);
        m.put("planCode", rows.isEmpty() ? DEFAULT_PLAN_CODE : rows.get(0).getPlanCode());
        m.put("rows", rows);
        if (rows.isEmpty()) {
            m.put("hint", "内置表里没有 AQL " + trim(a) + " / 检验水平 " + l
                    + " 这一档。要按国标补行，见 db/V7__sampling_plan.sql");
        }
        if (lotQty != null && lotQty > 0 && !rows.isEmpty()) {
            QcSamplingPlan hit = pick(rows, lotQty);
            if (hit != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("lotQty", lotQty);
                r.put("codeLetter", hit.getCodeLetter());
                r.put("sampleSize", Math.min(hit.getSampleSize(), lotQty));
                r.put("acceptQty", Math.min(hit.getAcceptQty(), Math.min(hit.getSampleSize(), lotQty)));
                m.put("resolved", r);
            } else {
                m.put("hint", "批量 " + lotQty + " 件超出内置表范围（最大 "
                        + rows.get(rows.size() - 1).getLotSizeMax() + " 件），建单会被拦下");
            }
        }
        return m;
    }

    // ==================== 内部实现 ====================

    private SamplingResult resolveFixed(QcStandard standard, String samplingType,
                                        int lotQty, Integer manualSampleQty) {
        int qty;
        if (manualSampleQty != null && manualSampleQty > 0) {
            qty = manualSampleQty;
        } else if ("SAMPLE".equals(samplingType)) {
            qty = standard.getSampleSize() == null ? 1 : Math.max(standard.getSampleSize(), 1);
        } else if ("FULL".equals(samplingType)) {
            // 全检 = 批次里每一件都要验
            qty = lotQty;
        } else {
            // 免检：不判批，只留一件占位记录
            qty = 1;
        }
        int ac = standard.getAllowedNgCount() == null ? 0 : Math.max(standard.getAllowedNgCount(), 0);

        String basis;
        if ("NONE".equals(samplingType)) {
            basis = "免检：不判定批次，仅留 1 件占位记录";
        } else if ("FULL".equals(samplingType)) {
            basis = "全检：批次 " + lotQty + " 件逐件检验，Ac=" + ac;
        } else {
            basis = "固定抽样：抽 " + qty + " 件，Ac=" + ac;
        }
        return new SamplingResult("FULL".equals(samplingType) ? "FULL" : MODE_FIXED,
                lotQty, qty, ac, null, basis);
    }

    private SamplingResult resolveByAql(QcStandard standard, int lotQty) {
        BigDecimal aql = normalizeAql(standard.getAql());
        String level = normalizeLevel(standard.getInspectionLevel());
        List<QcSamplingPlan> rows = list(aql, level);

        if (rows.isEmpty()) {
            throw BizException.of("没有 AQL " + trim(aql) + " / 检验水平 " + level
                    + " 的抽样方案。请先在抽样方案表里按国标补齐这一档，或把标准改回「固定抽样」");
        }

        QcSamplingPlan hit = pick(rows, lotQty);
        if (hit == null && lotQty < rows.get(0).getLotSizeMin()) {
            // 批量比表里最小档还小（例如只到货 1 件）：按最小档，样本量再被批量截断
            hit = rows.get(0);
        }
        if (hit == null) {
            throw BizException.of("批量 " + lotQty + " 件超出抽样方案表范围（当前最大 "
                    + rows.get(rows.size() - 1).getLotSizeMax()
                    + " 件）。请拆批后分批建单，或为该产品单独约定抽样方案");
        }

        // 批量比样本量还小时（批量 1~1 件），抽不满 n，按批量来
        boolean clamped = hit.getSampleSize() > lotQty;
        int n = clamped ? lotQty : hit.getSampleSize();
        // Ac 不能超过实际验的件数，否则「验 1 件 Ac=3」永远判合格
        int ac = Math.min(hit.getAcceptQty(), n);

        String basis = DEFAULT_PLAN_CODE.equals(hit.getPlanCode()) ? "GB/T 2828.1" : hit.getPlanCode();
        basis += " 一般检验水平 " + level + " / AQL " + trim(aql)
                + "：批量 " + lotQty + "，字码 " + hit.getCodeLetter()
                + "，抽 " + n + " 件，Ac=" + ac;
        if (clamped) {
            basis += "（批量小于样本量，按全检）";
        }
        return new SamplingResult(MODE_AQL, lotQty, n, ac, hit.getCodeLetter(), basis);
    }

    /**
     * 按批量命中一行方案。
     *
     * <p>纯函数，不碰数据库，单测直接喂 11 行国标数据验边界。
     */
    static QcSamplingPlan pick(List<QcSamplingPlan> rows, int lotQty) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (QcSamplingPlan p : rows) {
            int lo = p.getLotSizeMin() == null ? -1 : p.getLotSizeMin();
            int hi = p.getLotSizeMax() == null ? Integer.MAX_VALUE : p.getLotSizeMax();
            if (lotQty >= lo && lotQty <= hi) {
                return p;
            }
        }
        return null;
    }

    private static BigDecimal normalizeAql(BigDecimal aql) {
        if (aql == null || aql.signum() <= 0) {
            return DEFAULT_AQL;
        }
        // 统一到三位小数，和 numeric(6,3) 的存储精度对齐，避免 2.5 与 2.500 查不到行
        return aql.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return DEFAULT_LEVEL;
        }
        return level.trim().toUpperCase();
    }

    /** 2.500 → "2.5"，给人看的文字里不带无意义的尾零 */
    static String trim(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
}
