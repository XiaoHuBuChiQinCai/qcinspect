package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcItem;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import com.qc.inspect.judge.EnumOption;
import com.qc.inspect.mapper.QcItemMapper;
import com.qc.inspect.mapper.QcStandardItemMapper;
import com.qc.inspect.mapper.QcStandardMapper;
import com.qc.inspect.report.StandardDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检验标准版本管理。
 *
 * <p>核心规则：标准永不原地修改。改版 = 新增 version + 旧版置 OBSOLETE，
 * 这样历史检验单引用的版本依然可追溯。
 */
@Service
@RequiredArgsConstructor
public class StandardService {

    private final QcStandardMapper standardMapper;
    private final QcStandardItemMapper standardItemMapper;
    private final QcItemMapper itemMapper;
    private final SamplingPlanService samplingPlanService;
    private final ObjectMapper objectMapper;

    // ==================== 草稿维护 ====================

    /**
     * 为产品新建一份草稿标准。
     *
     * <p>同产品已有草稿时直接报错，避免出现两份草稿互相覆盖。
     */
    @Transactional
    public QcStandard createDraft(QcStandard req) {
        if (req.getProductId() == null) {
            throw BizException.of("必须指定产品");
        }
        long draftCount = standardMapper.selectCount(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, req.getProductId())
                .eq(QcStandard::getStatus, "DRAFT"));
        if (draftCount > 0) {
            throw BizException.of("该产品已有草稿版本，请先编辑或删除草稿，再新建");
        }
        normalizeSampling(req);

        LocalDateTime now = LocalDateTime.now();
        QcStandard std = new QcStandard();
        std.setStandardCode(req.getStandardCode());
        std.setProductId(req.getProductId());
        std.setVersion(1);
        std.setStatus("DRAFT");
        std.setSamplingType(req.getSamplingType());
        std.setSamplingPlan(req.getSamplingPlan());
        std.setAql(req.getAql());
        std.setInspectionLevel(req.getInspectionLevel());
        std.setSampleSize(req.getSampleSize());
        std.setAllowedNgCount(req.getAllowedNgCount());
        std.setRemark(req.getRemark());
        std.setCreatedAt(now);
        std.setUpdatedAt(now);
        standardMapper.insert(std);
        return std;
    }

    /** 修改草稿的表头信息（检验项走 saveItems） */
    @Transactional
    public QcStandard updateDraft(Long id, QcStandard req) {
        QcStandard std = require(id);
        assertEditable(std);
        normalizeSampling(req);
        std.setStandardCode(req.getStandardCode());
        std.setSamplingType(req.getSamplingType());
        std.setSamplingPlan(req.getSamplingPlan());
        std.setAql(req.getAql());
        std.setInspectionLevel(req.getInspectionLevel());
        std.setSampleSize(req.getSampleSize());
        std.setAllowedNgCount(req.getAllowedNgCount());
        std.setRemark(req.getRemark());
        std.setUpdatedAt(LocalDateTime.now());
        standardMapper.updateById(std);
        return std;
    }

    /** 删除草稿（生效中和已作废的版本不允许删，只能作废留档） */
    @Transactional
    public void deleteDraft(Long id) {
        QcStandard std = require(id);
        if (!"DRAFT".equals(std.getStatus())) {
            throw BizException.of("只有草稿可以删除。生效过的版本是质量记录，只能留档");
        }
        standardItemMapper.delete(new LambdaQueryWrapper<QcStandardItem>()
                .eq(QcStandardItem::getStandardId, id));
        standardMapper.deleteById(id);
    }

    // ==================== 检验项配置 ====================

    /**
     * 整体保存标准的检验项（先清后插，对应配置页一次性提交）。
     *
     * <p>校验都放在这里而不是前端，因为配置错了要到判定阶段才暴露，
     * 那时候可能是几百件已经验完了。
     *
     * @return 实际保存的项数
     */
    @Transactional
    public int saveItems(Long standardId, List<QcStandardItem> items) {
        QcStandard std = require(standardId);
        assertEditable(std);
        if (items == null || items.isEmpty()) {
            throw BizException.of("至少要配置一个检验项，否则建单时无项可填");
        }

        Set<Long> seen = new HashSet<>();
        List<QcStandardItem> toSave = new ArrayList<>(items.size());
        boolean hasCritical = false;

        for (QcStandardItem si : items) {
            if (si.getItemId() == null) {
                throw BizException.of("存在未选择检验项的行");
            }
            if (!seen.add(si.getItemId())) {
                throw BizException.of("同一个检验项不能重复配置");
            }
            QcItem item = itemMapper.selectById(si.getItemId());
            if (item == null) {
                throw BizException.of("检验项不存在或已删除：" + si.getItemId());
            }
            validateItem(std, item, si);
            hasCritical |= Boolean.TRUE.equals(si.getIsCritical());
            toSave.add(si);
        }
        if (!hasCritical) {
            throw BizException.of("至少要标记一个关键项。全部为非关键项时，任意一项超差都不会判死整批");
        }

        standardItemMapper.delete(new LambdaQueryWrapper<QcStandardItem>()
                .eq(QcStandardItem::getStandardId, standardId));

        LocalDateTime now = LocalDateTime.now();
        int seq = 0;
        for (QcStandardItem si : toSave) {
            si.setId(null);
            si.setStandardId(standardId);
            si.setSeq(seq += 10);
            si.setCreatedAt(now);
            si.setUpdatedAt(now);
            standardItemMapper.insert(si);
        }
        return toSave.size();
    }

    /** 单个检验项的配置校验。这里每一条都对应一个真实踩过的坑。 */
    private void validateItem(QcStandard std, QcItem item, QcStandardItem si) {
        String type = item.getValueType();

        // 测量方式与测点数
        if (!"MULTIPLE".equals(si.getValueMode())) {
            si.setValueMode("SINGLE");
            si.setPointCount(1);
        } else {
            if (si.getPointCount() == null || si.getPointCount() < 2) {
                throw BizException.of("「" + item.getItemName() + "」选了多点测量，测点数至少要 2");
            }
            if (si.getPointCount() > 20) {
                throw BizException.of("「" + item.getItemName() + "」测点数超过 20，实际现场没人会填这么多");
            }
        }

        switch (type) {
            case "NUMBER" -> {
                if (si.getMinValue() == null && si.getMaxValue() == null) {
                    throw BizException.of("「" + item.getItemName() + "」是数值项，必须给上限或下限，否则无法判定");
                }
                if (si.getMinValue() != null && si.getMaxValue() != null
                        && si.getMinValue().compareTo(si.getMaxValue()) > 0) {
                    throw BizException.of("「" + item.getItemName() + "」下限大于上限，检查是否填反了");
                }
                si.setOptionsJson(null);
            }
            case "ENUM" -> {
                List<EnumOption> options = parseOptions(si.getOptionsJson() != null
                        ? si.getOptionsJson() : item.getOptionsJson());
                if (options.isEmpty()) {
                    throw BizException.of("「" + item.getItemName() + "」是枚举项，必须配置选项");
                }
                if (options.stream().noneMatch(EnumOption::pass)) {
                    throw BizException.of("「" + item.getItemName() + "」的选项里没有任何一个判为合格，这样怎么填都是 NG");
                }
            }
            case "BOOL" -> {
                // boolPassValue 允许为 null，表示沿用主数据
                si.setOptionsJson(null);
                si.setMinValue(null);
                si.setMaxValue(null);
            }
            case "TEXT" -> {
                si.setOptionsJson(null);
                si.setMinValue(null);
                si.setMaxValue(null);
                si.setValueMode("SINGLE");
                si.setPointCount(1);
            }
            default -> throw BizException.of("未知的数据类型：" + type);
        }

        // 破坏性检验不能配全检：全检意味着每件都验，验完就报废
        if (Boolean.TRUE.equals(si.getIsDestructive()) && "FULL".equals(std.getSamplingType())) {
            throw BizException.of("「" + item.getItemName() + "」是破坏性检验，不能配在「全检」标准下。"
                    + "请把抽样方式改成抽检");
        }
    }

    private List<EnumOption> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            EnumOption[] arr = objectMapper.readValue(json, EnumOption[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            throw BizException.of("选项 JSON 格式不正确，应为 [{\"code\":\"OK\",\"name\":\"无\",\"pass\":true}]");
        }
    }

    private void normalizeSampling(QcStandard req) {
        if (req.getSamplingType() == null) {
            req.setSamplingType("FULL");
        }
        if (req.getSamplingPlan() == null || req.getSamplingPlan().isBlank()) {
            req.setSamplingPlan(SamplingPlanService.MODE_FIXED);
        }
        if (!SamplingPlanService.MODE_FIXED.equals(req.getSamplingPlan())
                && !SamplingPlanService.MODE_AQL.equals(req.getSamplingPlan())) {
            throw BizException.of("未知的抽样方案：" + req.getSamplingPlan());
        }

        if (SamplingPlanService.MODE_AQL.equals(req.getSamplingPlan())) {
            // AQL 模式下样本量由批量查表得出，标准上写死的件数和 Ac 都不再参与计算，
            // 但抽样方式必须落成 SAMPLE——否则「全检 + 按批量抽样」自相矛盾
            req.setSamplingType("SAMPLE");
            BigDecimal aql = req.getAql() == null ? SamplingPlanService.DEFAULT_AQL : req.getAql();
            String level = req.getInspectionLevel() == null || req.getInspectionLevel().isBlank()
                    ? SamplingPlanService.DEFAULT_LEVEL : req.getInspectionLevel();
            // 在这里就把方案存在性验掉：配错的 AQL 拖到建单才暴露，那时候货已经在检验台上了
            if (samplingPlanService.list(aql, level).isEmpty()) {
                throw BizException.of("抽样方案表里没有 AQL "
                        + SamplingPlanService.trim(aql.setScale(3, RoundingMode.HALF_UP))
                        + " / 检验水平 " + level
                        + " 这一档。请改选一档已有方案，或先按国标把这一档补进方案表");
            }
            req.setAql(aql);
            req.setInspectionLevel(level);
        } else {
            req.setAql(null);
            req.setInspectionLevel(null);
        }

        switch (req.getSamplingType()) {
            case "SAMPLE" -> {
                if (!SamplingPlanService.MODE_AQL.equals(req.getSamplingPlan())
                        && (req.getSampleSize() == null || req.getSampleSize() < 1)) {
                    throw BizException.of("抽检模式必须填写抽样件数");
                }
                if (SamplingPlanService.MODE_AQL.equals(req.getSamplingPlan()) && req.getSampleSize() == null) {
                    req.setSampleSize(1);
                }
            }
            case "FULL", "NONE" -> req.setSampleSize(1);
            default -> throw BizException.of("未知的抽样方式：" + req.getSamplingType());
        }
        if (req.getAllowedNgCount() == null || req.getAllowedNgCount() < 0) {
            req.setAllowedNgCount(0);
        }
    }

    private QcStandard require(Long id) {
        QcStandard std = standardMapper.selectById(id);
        if (std == null) {
            throw BizException.of("标准不存在：" + id);
        }
        return std;
    }

    private void assertEditable(QcStandard std) {
        if (!"DRAFT".equals(std.getStatus())) {
            throw BizException.of("只有草稿可以编辑。生效中的标准要改，请发布新版本");
        }
    }

    /**
     * 以指定标准为模板新建下一个版本的<strong>草稿</strong>。
     *
     * <p>为什么是草稿而不是直接生效：改版的目的就是「改点东西」，而生效的版本
     * 一律不可编辑（{@code assertEditable} 只放行 DRAFT）。早先这里直接建成 ACTIVE，
     * 结果发布完新版就锁死了——限值改不了、项加不了，「发布新版本」除了多一个
     * 数字以外什么也做不了。现在复制成草稿，配好再点「保存并生效」，旧版在
     * {@link #activate} 里自动作废。
     *
     * <p>同产品已有草稿时拒绝：两份草稿谁也不知道哪份是对的。
     */
    @Transactional
    public QcStandard publishNewVersion(Long sourceId) {
        QcStandard src = standardMapper.selectById(sourceId);
        if (src == null) {
            throw BizException.of("标准不存在：" + sourceId);
        }

        List<QcStandard> siblings = standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, src.getProductId()));

        boolean hasDraft = siblings.stream().anyMatch(s -> "DRAFT".equals(s.getStatus()));
        if (hasDraft) {
            throw BizException.of("该产品已有草稿版本，先把草稿配好生效或删掉，再发布新版本");
        }

        int maxVersion = siblings.stream()
                .mapToInt(s -> s.getVersion() == null ? 0 : s.getVersion())
                .max()
                .orElse(0);

        LocalDateTime now = LocalDateTime.now();

        QcStandard nw = new QcStandard();
        nw.setStandardCode(src.getStandardCode());
        nw.setProductId(src.getProductId());
        nw.setVersion(maxVersion + 1);
        nw.setStatus("DRAFT");
        nw.setSamplingType(src.getSamplingType());
        nw.setSamplingPlan(src.getSamplingPlan());
        nw.setAql(src.getAql());
        nw.setInspectionLevel(src.getInspectionLevel());
        nw.setSampleSize(src.getSampleSize());
        nw.setAllowedNgCount(src.getAllowedNgCount());
        nw.setRemark("由 v" + src.getVersion() + " 复制，配置后生效");
        nw.setCreatedAt(now);
        standardMapper.insert(nw);

        List<QcStandardItem> items = standardItemMapper.selectList(
                new LambdaQueryWrapper<QcStandardItem>()
                        .eq(QcStandardItem::getStandardId, src.getId())
                        .orderByAsc(QcStandardItem::getSeq));
        for (QcStandardItem si : items) {
            QcStandardItem copy = new QcStandardItem();
            BeanUtils.copyProperties(si, copy);
            copy.setId(null);
            copy.setStandardId(nw.getId());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            standardItemMapper.insert(copy);
        }
        return nw;
    }

    /**
     * 两个版本之间的差异。
     *
     * <p>{@code againstId} 为空时自动对比同产品的上一版——「这次改了什么」是
     * 99% 的场景，不该逼人先记住版本号。没有上一版时返回首版标记，changes 为空。
     */
    public Map<String, Object> diff(Long id, Long againstId) {
        QcStandard after = require(id);

        QcStandard before;
        if (againstId != null) {
            before = require(againstId);
            if (!java.util.Objects.equals(before.getProductId(), after.getProductId())) {
                throw BizException.of("只能对比同一产品的两个版本");
            }
        } else {
            before = standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                            .eq(QcStandard::getProductId, after.getProductId())
                            .lt(QcStandard::getVersion, after.getVersion()))
                    .stream()
                    .max(java.util.Comparator.comparingInt(s -> s.getVersion() == null ? 0 : s.getVersion()))
                    .orElse(null);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("to", brief(after));
        if (before == null) {
            m.put("from", null);
            m.put("firstVersion", true);
            m.put("verdict", StandardDiff.NEUTRAL);
            m.put("verdictText", "第一个版本");
            m.put("advice", "没有可对比的更早版本");
            m.put("changes", List.of());
            m.put("summary", List.of());
            return m;
        }

        List<QcStandardItem> beforeItems = itemsOf(before.getId());
        List<QcStandardItem> afterItems = itemsOf(after.getId());
        Map<Long, QcItem> itemIndex = itemIndex(beforeItems, afterItems);

        StandardDiff.Result r = StandardDiff.compare(before, after, beforeItems, afterItems, itemIndex);

        m.put("from", brief(before));
        m.put("firstVersion", false);
        m.put("verdict", r.verdict());
        m.put("verdictText", r.verdictText());
        m.put("advice", r.advice());
        m.put("changes", r.changes());
        m.put("summary", r.summary());
        return m;
    }

    private List<QcStandardItem> itemsOf(Long standardId) {
        return standardItemMapper.selectList(new LambdaQueryWrapper<QcStandardItem>()
                .eq(QcStandardItem::getStandardId, standardId)
                .orderByAsc(QcStandardItem::getSeq));
    }

    /** 一次查完两个版本涉及的检验项，别逐行 selectById */
    private Map<Long, QcItem> itemIndex(List<QcStandardItem> a, List<QcStandardItem> b) {
        Set<Long> ids = new HashSet<>();
        a.forEach(si -> ids.add(si.getItemId()));
        b.forEach(si -> ids.add(si.getItemId()));
        ids.remove(null);
        Map<Long, QcItem> m = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return m;
        }
        for (QcItem item : itemMapper.selectBatchIds(ids)) {
            m.put(item.getId(), item);
        }
        return m;
    }

    private Map<String, Object> brief(QcStandard s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("standardCode", s.getStandardCode());
        m.put("version", s.getVersion());
        m.put("status", s.getStatus());
        m.put("effectiveFrom", s.getEffectiveFrom());
        m.put("createdAt", s.getCreatedAt());
        m.put("remark", s.getRemark());
        return m;
    }

    /**
     * 启用一个草稿版本（用于首次建标准）。
     */
    @Transactional
    public void activate(Long standardId) {
        QcStandard target = standardMapper.selectById(standardId);
        if (target == null) {
            throw BizException.of("标准不存在：" + standardId);
        }
        if ("ACTIVE".equals(target.getStatus())) {
            return;
        }
        List<QcStandard> siblings = standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, target.getProductId())
                .eq(QcStandard::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now();
        for (QcStandard s : siblings) {
            s.setStatus("OBSOLETE");
            s.setUpdatedAt(now);
            standardMapper.updateById(s);
        }
        target.setStatus("ACTIVE");
        target.setEffectiveFrom(LocalDate.now());
        target.setUpdatedAt(now);
        standardMapper.updateById(target);
    }
}
