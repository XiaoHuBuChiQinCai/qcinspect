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
import java.util.Set;

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

    public List<QcDefect> list(Long inspectionId, String status) {
        return defectMapper.selectList(new LambdaQueryWrapper<QcDefect>()
                .eq(inspectionId != null, QcDefect::getInspectionId, inspectionId)
                .eq(status != null && !status.isBlank(), QcDefect::getDispositionStatus, status)
                .orderByDesc(QcDefect::getId));
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

        if ("DONE".equals(target) && "REWORK".equals(d.getDisposition())
                && d.getRecheckInspectionId() == null) {
            throw BizException.of("返工完成后必须挂一张复检单，否则无法证明返工后的产品是合格的");
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
        return defectMapper.pareto(productId, from);
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
