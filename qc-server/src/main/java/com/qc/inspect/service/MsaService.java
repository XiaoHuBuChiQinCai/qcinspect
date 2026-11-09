package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcMsaStudy;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.mapper.QcMsaStudyMapper;
import com.qc.inspect.mapper.QcProductMapper;
import com.qc.inspect.msa.GrrStudy;
import com.qc.inspect.msa.MsaView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量具测量系统分析（GR&amp;R）。
 *
 * <p>这一层只做三件事：把录进来的读数校一遍、把量具和规格快照下来、把结果算出来。
 * 算法本身在 {@link GrrStudy} 里，不依赖数据库，单测直接打；这里不复制任何公式。
 *
 * <p>两条刻意的取舍：
 * <ul>
 *   <li><b>不收「结构参数」。</b> 评价人数、零件数、重复次数一律从读数本身的维度推出来，
 *       不让前端再报一遍。否则「报了 3 人、读数给了 2 组」这种不一致会一路写到库里</li>
 *   <li><b>只存原始读数，%GRR 现算。</b> 结论落库的话，哪天公式或系数改了，
 *       旧单上的数与新算的对不上，同一个指标两个出处</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MsaService {

    private static final Logger log = LoggerFactory.getLogger(MsaService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final QcMsaStudyMapper studyMapper;
    private final DocNoService docNoService;
    private final GaugeService gaugeService;
    private final QcProductMapper productMapper;
    private final ObjectMapper json;

    /** 录入请求。刻意不含 appraiserCount / partCount / trialCount：那三个从读数推 */
    public record CreateReq(
            Long gaugeId,
            Long productId,
            Long itemId,
            String characteristic,
            String unit,
            BigDecimal lsl,
            BigDecimal usl,
            List<String> appraisers,
            List<List<List<Double>>> readings,
            String remark) {
    }

    // ==================== 录入 ====================

    @Transactional
    public MsaView create(CreateReq req) {
        if (req == null) {
            throw BizException.of("没有收到研究内容");
        }
        String characteristic = trimToNull(req.characteristic());
        if (characteristic == null) {
            throw BizException.of("要研究的特性必填——写清是哪个尺寸或哪一项，三个月后回头看这张单才有意义");
        }

        List<String> names = cleanNames(req.appraisers());
        double[][][] x = toArray(req.readings(), names.size());

        GrrStudy.Check shape = GrrStudy.checkShape(x.length, x[0].length, x[0][0].length);
        if (!shape.ok()) {
            throw BizException.of(shape.message());
        }
        GrrStudy.Check readings = GrrStudy.checkReadings(x);
        if (!readings.ok()) {
            throw BizException.of(readings.message());
        }

        BigDecimal lsl = req.lsl();
        BigDecimal usl = req.usl();
        if (lsl != null && usl != null && lsl.compareTo(usl) >= 0) {
            throw BizException.of("规格下限 " + plain(lsl) + " 不小于上限 " + plain(usl) + "，检查一下是不是填反了");
        }

        if (req.gaugeId() == null) {
            throw BizException.of("要选量具——GR&R 研究的对象就是那把尺，不选它这张单没有意义");
        }
        // 停用/报废/未校准/超期的量具直接拒：拿一把超期的尺做研究，算出来的结论没人敢用
        QcGauge gauge = gaugeService.assertUsable(req.gaugeId(), characteristic);

        String studyNo = docNoService.next("MSA", 3,
                () -> studyMapper.countByDayPrefix(LocalDate.now().format(DAY)));

        LocalDateTime now = LocalDateTime.now();
        QcMsaStudy s = new QcMsaStudy();
        s.setStudyNo(studyNo);
        s.setGaugeId(gauge.getId());
        s.setGaugeCode(gauge.getGaugeCode());
        s.setGaugeName(gauge.getGaugeName());
        s.setProductId(req.productId());
        s.setItemId(req.itemId());
        s.setCharacteristic(characteristic);
        s.setUnit(trimToNull(req.unit()));
        s.setLsl(lsl);
        s.setUsl(usl);
        s.setAppraiserCount(names.size());
        s.setPartCount(x[0].length);
        s.setTrialCount(x[0][0].length);
        s.setAppraisers(write(names));
        s.setReadings(write(req.readings()));
        s.setRemark(trimToNull(req.remark()));
        s.setDelFlag(0);
        s.setCreatedBy(UserContext.username());
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        studyMapper.insert(s);

        return view(s, true, productNames(List.of(s)));
    }

    // ==================== 查询 ====================

    /**
     * 分页。keyword 同时匹配研究号、量具编号/名称、特性——
     * 现场问的通常是「这把卡尺做过 GRR 吗」或者「上次外径那个研究呢」。
     *
     * <p>{@code verdict}（GOOD / MARGINAL / BAD）是**算出来**的，SQL 里没有这一列，
     * 所以带它的时候先取全量、过滤完再在内存里分页。研究记录一年也就几十条，
     * 这点开销换「筛完总数还对得上」是值的；反过来「先分页再过滤」会出现
     * 「共 5 条，这页只显示 1 条」这种越看越可疑的界面。
     */
    public Page<MsaView> page(long current, long size, String keyword, Long gaugeId, String verdict) {
        LambdaQueryWrapper<QcMsaStudy> w = new LambdaQueryWrapper<>();
        if (gaugeId != null) {
            w.eq(QcMsaStudy::getGaugeId, gaugeId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like(QcMsaStudy::getStudyNo, k)
                    .or().like(QcMsaStudy::getGaugeCode, k)
                    .or().like(QcMsaStudy::getGaugeName, k)
                    .or().like(QcMsaStudy::getCharacteristic, k));
        }
        w.orderByDesc(QcMsaStudy::getCreatedAt).orderByDesc(QcMsaStudy::getId);

        boolean filterByVerdict = verdict != null && !verdict.isBlank();
        if (!filterByVerdict) {
            Page<QcMsaStudy> found = studyMapper.selectPage(new Page<>(current, size), w);
            Map<Long, String> names = productNames(found.getRecords());
            Page<MsaView> out = new Page<>(found.getCurrent(), found.getSize(), found.getTotal());
            out.setRecords(found.getRecords().stream().map(e -> view(e, false, names)).toList());
            return out;
        }

        List<QcMsaStudy> all = studyMapper.selectList(w);
        Map<Long, String> names = productNames(all);
        List<MsaView> matched = all.stream()
                .map(e -> view(e, false, names))
                .filter(v -> v.result() != null && verdict.equals(v.result().verdict()))
                .toList();
        long from = Math.max(0, (current - 1) * size);
        long to = Math.min(matched.size(), from + size);
        Page<MsaView> out = new Page<>(current, size, matched.size());
        out.setRecords(from >= matched.size() ? List.of() : matched.subList((int) from, (int) to));
        return out;
    }

    public MsaView detail(Long id) {
        QcMsaStudy s = studyMapper.selectById(id);
        if (s == null) {
            throw BizException.of("研究记录不存在：" + id);
        }
        return view(s, true, productNames(List.of(s)));
    }

    /**
     * 作废（逻辑删）。
     *
     * <p>研究记录本身不可改：读数一旦是编的，改一个数字就能把它变成「合格的」，
     * 那这条记录就失去了作为证据的价值。录错了只能作废重做，作废要写理由。
     */
    @Transactional
    public void cancel(Long id, String reason) {
        QcMsaStudy s = studyMapper.selectById(id);
        if (s == null) {
            throw BizException.of("研究记录不存在：" + id);
        }
        String me = UserContext.username();
        boolean mine = me.equals(s.getCreatedBy());
        if (!UserContext.isAdmin() && !mine) {
            throw BizException.of("只能作废自己录入的研究；这张是 " + s.getCreatedBy() + " 录的");
        }
        String why = trimToNull(reason);
        if (why == null || why.length() < 2) {
            throw BizException.of("作废要写理由——这张单会从量具履历里消失，得有人知道为什么");
        }
        String remark = s.getRemark() == null ? "" : s.getRemark() + " | ";
        s.setRemark(remark + "作废：" + why);
        s.setUpdatedBy(me);
        s.setUpdatedAt(LocalDateTime.now());
        studyMapper.updateById(s);
        studyMapper.deleteById(id);
        log.info("MSA 研究 {} 由 {} 作废：{}", s.getStudyNo(), me, why);
    }

    // ==================== 内部 ====================

    private MsaView view(QcMsaStudy s, boolean withReadings, Map<Long, String> productNames) {
        List<String> names = readNames(s.getAppraisers());
        List<List<List<Double>>> readings = readReadings(s.getReadings());
        GrrStudy.Result result = compute(readings, s.getLsl(), s.getUsl());
        return new MsaView(
                s.getId(), s.getStudyNo(),
                s.getGaugeId(), s.getGaugeCode(), s.getGaugeName(),
                s.getProductId(), s.getProductId() == null ? null : productNames.get(s.getProductId()),
                s.getItemId(),
                s.getCharacteristic(), s.getUnit(),
                s.getLsl(), s.getUsl(),
                s.getAppraiserCount() == null ? 0 : s.getAppraiserCount(),
                s.getPartCount() == null ? 0 : s.getPartCount(),
                s.getTrialCount() == null ? 0 : s.getTrialCount(),
                names,
                withReadings ? readings : null,
                result,
                s.getRemark(), s.getCreatedBy(), s.getCreatedAt());
    }

    private GrrStudy.Result compute(List<List<List<Double>>> readings,
                                    BigDecimal lsl, BigDecimal usl) {
        if (readings == null || readings.isEmpty()) {
            return null;
        }
        try {
            double[][][] x = new double[readings.size()][][];
            for (int i = 0; i < readings.size(); i++) {
                List<List<Double>> parts = readings.get(i);
                x[i] = new double[parts.size()][];
                for (int j = 0; j < parts.size(); j++) {
                    List<Double> trials = parts.get(j);
                    x[i][j] = new double[trials.size()];
                    for (int k = 0; k < trials.size(); k++) {
                        Double v = trials.get(k);
                        if (v == null) {
                            return null;
                        }
                        x[i][j][k] = v;
                    }
                }
            }
            return GrrStudy.compute(x, toDouble(lsl), toDouble(usl));
        } catch (RuntimeException e) {
            // 读不出来就把结论留空，别让一条坏数据把整页列表打成 500
            log.warn("MSA 读数解析失败，结论留空", e);
            return null;
        }
    }

    private List<String> cleanNames(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of("要把参与测量的评价人填上——出了再现性问题，第一个要问的就是「谁跟谁对不上」");
        }
        List<String> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            String n = trimToNull(raw.get(i));
            if (n == null) {
                throw BizException.of("第 " + (i + 1) + " 位评价人没填名字");
            }
            out.add(n);
        }
        if (new HashSet<>(out).size() != out.size()) {
            throw BizException.of("评价人重名了：" + String.join("、", out)
                    + "。加个区分，比如「张三(一车间)」，否则评审时分不清是谁测的");
        }
        return out;
    }

    /**
     * 三维读数转数组，顺便把「形状对不齐」说成人话。
     *
     * <p>不做这一步的话，后面的计算会在某个下标越界处抛 500，
     * 而人看到的是「服务器错误」，不知道是自己漏填了 3 号零件的第二次。
     */
    private double[][][] toArray(List<List<List<Double>>> raw, int appraisers) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of("读数没录入");
        }
        if (raw.size() != appraisers) {
            throw BizException.of("填了 " + appraisers + " 位评价人，读数却只有 " + raw.size() + " 组，对不上");
        }
        double[][][] x = new double[appraisers][][];
        int parts = -1;
        int trials = -1;
        for (int i = 0; i < appraisers; i++) {
            List<List<Double>> row = raw.get(i);
            if (row == null || row.isEmpty()) {
                throw BizException.of("第 " + (i + 1) + " 位评价人一件都没测");
            }
            if (parts < 0) {
                parts = row.size();
            } else if (row.size() != parts) {
                throw BizException.of("第 " + (i + 1) + " 位评价人测了 " + row.size()
                        + " 件，前面那位测了 " + parts + " 件——件数要对齐，不然算不出零件变差");
            }
            x[i] = new double[parts][];
            for (int j = 0; j < parts; j++) {
                List<Double> cell = row.get(j);
                if (cell == null || cell.isEmpty()) {
                    throw BizException.of("第 " + (i + 1) + " 位评价人测第 " + (j + 1) + " 件这一格没填");
                }
                if (trials < 0) {
                    trials = cell.size();
                } else if (cell.size() != trials) {
                    throw BizException.of("第 " + (i + 1) + " 位评价人测第 " + (j + 1) + " 件填了 " + cell.size()
                            + " 次，别的格子是 " + trials + " 次——重复次数要一样");
                }
                x[i][j] = new double[trials];
                for (int k = 0; k < trials; k++) {
                    Double v = cell.get(k);
                    if (v == null) {
                        throw BizException.of("第 " + (i + 1) + " 位评价人测第 " + (j + 1) + " 件第 " + (k + 1) + " 次没填");
                    }
                    x[i][j][k] = v;
                }
            }
        }
        return x;
    }

    private Map<Long, String> productNames(List<QcMsaStudy> rows) {
        List<Long> ids = rows.stream()
                .map(QcMsaStudy::getProductId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> map = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (QcProduct p : productMapper.selectBatchIds(ids)) {
            map.put(p.getId(), p.getProductName());
        }
        return map;
    }

    private List<String> readNames(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("MSA 评价人字段解析失败：{}", s, e);
            return List.of();
        }
    }

    private List<List<List<Double>>> readReadings(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s, new TypeReference<List<List<List<Double>>>>() {
            });
        } catch (Exception e) {
            log.warn("MSA 读数解析失败", e);
            return List.of();
        }
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw BizException.of("读数保存失败：" + e.getMessage());
        }
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
