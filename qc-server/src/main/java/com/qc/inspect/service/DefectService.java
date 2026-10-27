package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 不良处置闭环。
 *
 * <p>质检系统的价值不在「判出 NG」，在 NG 之后。判出 NG 只写一条记录，
 * 客户拿去做体系审核会被问三个问题：怎么处置的、谁批的、返工后复检了吗。
 * 这个类就是回答这三个问题的地方。
 */
@Service
@RequiredArgsConstructor
public class DefectService {

    /** 处置方式 */
    private static final Set<String> DISPOSITIONS = Set.of("REWORK", "SCRAP", "CONCESSION", "RETURN");

    private final QcDefectMapper defectMapper;
    private final QcInspectionMapper inspectionMapper;
    private final NoticeService noticeService;

    public List<QcDefect> list(Long inspectionId, String status) {
        List<QcDefect> list = defectMapper.selectList(new LambdaQueryWrapper<QcDefect>()
                .eq(inspectionId != null, QcDefect::getInspectionId, inspectionId)
                .eq(status != null && !status.isBlank(), QcDefect::getDispositionStatus, status)
                .orderByDesc(QcDefect::getId));
        fillRecheck(list);
        return list;
    }

    /**
     * 把复检单的单号/状态/结论贴回不良记录。
     *
     * <p>为什么不在 SQL 里 join：列表是「一屏看完」的场景，一次批量查询比
     * N 次 join 更好控；更重要的是，处置列表页最常被问的一句话是
     * 「这条返工到底修好了没有」——只给一个内部 id，等于让人再去搜一次。
     */
    private void fillRecheck(List<QcDefect> list) {
        if (list.isEmpty()) {
            return;
        }
        Set<Long> ids = list.stream().map(QcDefect::getRecheckInspectionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, QcInspection> map = inspectionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(QcInspection::getId, Function.identity(), (a, b) -> a));
        for (QcDefect d : list) {
            QcInspection r = map.get(d.getRecheckInspectionId());
            if (r == null) {
                continue;
            }
            d.setRecheckInspectionNo(r.getInspectionNo());
            d.setRecheckStatus(r.getStatus());
            d.setRecheckResult(r.getResult());
        }
    }

    /**
     * 提交处置方案。
     *
     * <p>状态流转：
     * <ul>
     *   <li>返工 REWORK：PENDING → PROCESSING → DONE（DONE 必须挂复检单）</li>
     *   <li>报废 SCRAP：直接 DONE</li>
     *   <li>退货 RETURN：PENDING → PROCESSING → DONE</li>
     *   <li>让步接收 CONCESSION：走 {@link #concession}，需要授权人签字</li>
     * </ul>
     */
    @Transactional
    public QcDefect dispose(Long defectId, String disposition, String status, String note, String operator) {
        QcDefect d = require(defectId);
        if (disposition != null) {
            if (!DISPOSITIONS.contains(disposition)) {
                throw BizException.of("未知的处置方式：" + disposition);
            }
            d.setDisposition(disposition);
        }
        if (d.getDisposition() == null) {
            throw BizException.of("必须先选择处置方式");
        }
        if ("CONCESSION".equals(d.getDisposition())) {
            throw BizException.of("让步接收需要走审批，请用「让步接收」接口并填写理由与授权人");
        }

        String target = status;
        if (target == null) {
            // 没指定就按处置方式给一个默认下一步
            target = "SCRAP".equals(d.getDisposition()) ? "DONE" : "PROCESSING";
        }
        validateTransition(d, target);

        if ("DONE".equals(target) && "REWORK".equals(d.getDisposition())) {
            if (d.getRecheckInspectionId() == null) {
                throw BizException.of("返工完成后必须挂一张复检单，否则无法证明返工后的产品是合格的");
            }
            // 挂了复检单不等于复检合格。返工没修好却把不良标成 DONE，
            // 等于在质量记录上写「已处理」，而实物还是坏的——这是最危险的一种数据失真。
            QcInspection recheck = inspectionMapper.selectById(d.getRecheckInspectionId());
            if (recheck == null) {
                throw BizException.of("挂的复检单不存在（ID " + d.getRecheckInspectionId() + "），请重新挂接");
            }
            if ("DRAFT".equals(recheck.getStatus())) {
                throw BizException.of("复检单 " + recheck.getInspectionNo()
                        + " 还没提交，现在标完成没有依据。请先填完复检并提交");
            }
            if ("FAIL".equals(recheck.getResult())) {
                throw BizException.of("复检单 " + recheck.getInspectionNo()
                        + " 判定为不合格：返工没修好，不能算闭环。请重新返工，或把处置改为报废");
            }
            if (!"PASS".equals(recheck.getResult())) {
                throw BizException.of("复检单 " + recheck.getInspectionNo()
                        + " 没有判定结论（" + recheck.getResult() + "），无法作为返工合格的依据");
            }
        }

        d.setDispositionStatus(target);
        if (note != null && !note.isBlank()) {
            d.setDispositionNote(note);
        }
        d.setDispositionBy(operator);
        d.setDispositionAt(LocalDateTime.now());
        defectMapper.updateById(d);
        return d;
    }

    /**
     * 让步接收审批：ISO 体系里唯一需要「有权签字的人点头」的处置方式。
     */
    @Transactional
    public QcDefect concession(Long defectId, String reason, String approvedBy) {
        QcDefect d = require(defectId);
        if (reason == null || reason.isBlank()) {
            throw BizException.of("让步接收必须填写理由，审核时会被追问「为什么能让」");
        }
        if (approvedBy == null || approvedBy.isBlank()) {
            throw BizException.of("让步接收必须记录授权人");
        }
        d.setDisposition("CONCESSION");
        d.setDispositionStatus("WAIVED");
        d.setConcessionReason(reason);
        d.setConcessionApprovedBy(approvedBy);
        d.setConcessionApprovedAt(LocalDateTime.now());
        d.setDispositionBy(approvedBy);
        d.setDispositionAt(LocalDateTime.now());
        defectMapper.updateById(d);

        // 检验员原本可能正等着返工，特采放行必须让他知道，否则会白干一道工序
        QcInspection ins = d.getInspectionId() == null ? null : inspectionMapper.selectById(d.getInspectionId());
        String no = ins == null ? null : ins.getInspectionNo();
        noticeService.push(d.getCreatedBy(), NoticeService.TYPE_CONCESSION,
                "不良已让步接收：" + (no == null ? d.getDefectName() : no),
                "第 " + d.getPieceNo() + " 件「" + d.getDefectName() + "」由 " + approvedBy + " 批准让步放行，无需处置。理由：" + reason,
                "DEFECT", d.getId(), no);
        return d;
    }

    /** 挂复检单：形成「原单 NG → 返工 → 复检」的追溯链 */
    @Transactional
    public QcDefect linkRecheck(Long defectId, Long recheckInspectionId) {
        QcDefect d = require(defectId);
        QcInspection recheck = inspectionMapper.selectById(recheckInspectionId);
        if (recheck == null) {
            throw BizException.of("复检单不存在：" + recheckInspectionId);
        }
        if (recheck.getId().equals(d.getInspectionId())) {
            throw BizException.of("复检单不能是原单本身");
        }
        d.setRecheckInspectionId(recheckInspectionId);
        defectMapper.updateById(d);
        return d;
    }

    /** 不良帕累托 */
    public List<Map<String, Object>> pareto(Long productId, String from) {
        return defectMapper.pareto(productId, normalizeFrom(from));
    }

    /**
     * 时间筛选参数归一。
     *
     * <p>空串不等于 null：`?from=` 这种写法 Spring 会绑成 {@code ""}，而 SQL 里的
     * {@code CAST('' AS timestamptz)} 直接报 {@code invalid input syntax}。
     * 语义上「不按时间筛」，所以空白一律当没传。
     *
     * <p>其余非法值提前拦成中文提示：PG 的原话是
     * {@code invalid input syntax for type timestamp with time zone}，
     * 对车间里的人来说等于没说。
     */
    private String normalizeFrom(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        String v = from.trim();
        try {
            java.time.OffsetDateTime.parse(v);
            return v;
        } catch (java.time.format.DateTimeParseException ignored) {
            // 不是带时区的 ISO，再试不带时区的
        }
        try {
            LocalDateTime.parse(v);
            return v;
        } catch (java.time.format.DateTimeParseException ignored) {
            // 再试纯日期
        }
        try {
            java.time.LocalDate.parse(v);
            return v;
        } catch (java.time.format.DateTimeParseException e) {
            throw BizException.of("时间筛选参数格式不对：" + v + "（可用 2026-07-29 或 2026-07-29T10:00:00Z）");
        }
    }

    private void validateTransition(QcDefect d, String target) {
        String cur = d.getDispositionStatus() == null ? "PENDING" : d.getDispositionStatus();
        boolean ok = switch (cur) {
            case "PENDING" -> "PROCESSING".equals(target) || "DONE".equals(target);
            case "PROCESSING" -> "DONE".equals(target);
            case "DONE", "WAIVED" -> false;
            default -> false;
        };
        if (!ok) {
            throw BizException.of("处置状态不能从 " + statusName(cur) + " 直接变为 " + statusName(target));
        }
    }

    private String statusName(String s) {
        return switch (s) {
            case "PENDING" -> "待处理";
            case "PROCESSING" -> "处理中";
            case "DONE" -> "已完成";
            case "WAIVED" -> "已让步";
            default -> s;
        };
    }

    private QcDefect require(Long id) {
        QcDefect d = defectMapper.selectById(id);
        if (d == null) {
            throw BizException.of("不良记录不存在：" + id);
        }
        return d;
    }
}
