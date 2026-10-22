package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcShipment;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcShipmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出货登记与出货前校验。
 *
 * <p>为什么出货要拦一道：{@code qc_shipment} 是追溯链的终点，客诉来了靠它反查
 * 「这批货发给了谁」。如果登记环节什么都不校验，就会出现「不合格批次照常出货」
 * 和「超发」——两个都是质量体系里最难看的问题，而且事后无法从数据上恢复。
 *
 * <p>校验尺度是「默认关门，允许显式开」：不合格批次、还没结论的批次都能出，
 * 但必须调用方明确传 force。默默放行和一刀切禁止都不对——
 * 前者是质量事故的温床，后者会让车间绕过系统手工记账。
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final QcBatchMapper batchMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcShipmentMapper shipmentMapper;

    /** 出货前置信息：前端弹窗用来显示「还剩多少可发」「检验结论是什么」 */
    public Map<String, Object> context(Long batchId) {
        QcBatch batch = requireBatch(batchId);
        int shipped = shipmentMapper.shippedQty(batchId);
        int total = batch.getQty() == null ? 0 : batch.getQty();
        QcInspection latest = latestConclusion(batchId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("batchNo", batch.getBatchNo());
        m.put("batchQty", total);
        m.put("shippedQty", shipped);
        m.put("remainingQty", Math.max(total - shipped, 0));
        m.put("inspectionNo", latest == null ? null : latest.getInspectionNo());
        m.put("result", latest == null ? null : latest.getResult());
        m.put("blocking", blockingReason(latest));
        return m;
    }

    /**
     * 登记出货。
     *
     * @param force 质检结论不满足放行条件时，是否确认为「让步/例外出货」
     */
    public QcShipment create(Long batchId, String customer, Integer qty, LocalDateTime shippedAt,
                             String remark, boolean force, String operator) {
        QcBatch batch = requireBatch(batchId);
        if (customer == null || customer.isBlank()) {
            throw BizException.of("必须填写客户。没有客户名字，这张出货记录在客诉时等于没用");
        }
        if (qty == null || qty <= 0) {
            throw BizException.of("出货数量必须大于 0");
        }

        int total = batch.getQty() == null ? 0 : batch.getQty();
        int shipped = shipmentMapper.shippedQty(batchId);
        if (total > 0 && shipped + qty > total) {
            throw BizException.of("超出批次数量：批次 " + total + " 件，已出货 " + shipped
                    + " 件，本次最多还能出 " + Math.max(total - shipped, 0) + " 件");
        }

        QcInspection latest = latestConclusion(batchId);
        String blocking = blockingReason(latest);
        if (blocking != null && !force) {
            throw BizException.of(blocking + "。确认要出的话，勾选「例外放行」再提交");
        }

        QcShipment s = new QcShipment();
        s.setShipmentNo(nextNo());
        s.setBatchId(batchId);
        s.setCustomer(customer.trim());
        s.setQty(qty);
        s.setShippedAt(shippedAt == null ? LocalDateTime.now() : shippedAt);
        // 例外放行必须留下痕迹：一年后看到这张单，要知道它当年是带着问题出去的
        s.setRemark(force && blocking != null
                ? "[例外放行] " + (remark == null || remark.isBlank() ? blocking : remark.trim())
                : (remark == null ? null : remark.trim()));
        s.setCreatedBy(operator);
        s.setCreatedAt(LocalDateTime.now());
        shipmentMapper.insert(s);
        return s;
    }

    /**
     * 放行判断。返回 null 表示可以直接出货，返回字符串表示拦下的原因。
     *
     * <p>只看最新一张已提交/已归档的检验单：批次可能返工后重检，
     * 早期那张 FAIL 已经被后续 PASS 覆盖，不该继续拦着。
     */
    private String blockingReason(QcInspection latest) {
        if (latest == null) {
            return "该批次还没有任何检验结论";
        }
        if ("FAIL".equals(latest.getResult())) {
            return "该批次最新检验结论为不合格（" + latest.getInspectionNo() + "）";
        }
        if ("NA".equals(latest.getResult())) {
            return "该批次检验单尚未判定（" + latest.getInspectionNo() + "）";
        }
        return null;
    }

    private QcInspection latestConclusion(Long batchId) {
        return inspectionMapper.selectList(new LambdaQueryWrapper<QcInspection>()
                        .eq(QcInspection::getBatchId, batchId)
                        .in(QcInspection::getStatus, "SUBMITTED", "CLOSED")
                        .orderByDesc(QcInspection::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private QcBatch requireBatch(Long batchId) {
        QcBatch batch = batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null) {
            throw BizException.of("批次不存在");
        }
        return batch;
    }

    private String nextNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long count = shipmentMapper.countByDayPrefix(day);
        return "SH" + day + String.format("%03d", count + 1);
    }
}
