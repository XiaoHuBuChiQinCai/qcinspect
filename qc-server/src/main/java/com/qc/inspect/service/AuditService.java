package com.qc.inspect.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.dto.AuditQuery;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcInspectionLog;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcInspectionLogMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 操作日志的全局检索。
 *
 * <p>单据详情页的轨迹回答「这张单发生过什么」，这里回答的是反过来的问题：
 * 「谁在什么时候作废过单」「已归档的单被谁解锁过」。内审和管理者要的是后者，
 * 而数据一直都在，只是此前没有入口。
 *
 * <p>返回 {@link AuditRow} 而不是裸的日志实体：查日志的人关心的是「哪张单、哪个批次」，
 * 让他拿到一个 investigationId 再去别处换算，等于把成本推给使用者。
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final QcInspectionLogMapper logMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;

    /** 一页日志，附带单号/批次号/产品名 */
    public Page<AuditRow> page(AuditQuery query, long current, long size) {
        Page<QcInspectionLog> p = new Page<>(current, size);
        logMapper.selectPage(p, query.toWrapper().orderByDesc(QcInspectionLog::getId));

        Page<AuditRow> out = new Page<>(current, size, p.getTotal());
        out.setRecords(enrich(p.getRecords()));
        return out;
    }

    /** 导出用：不分页，但给个上限，免得某天有人点导出把内存打满 */
    public List<AuditRow> list(AuditQuery query, int limit) {
        int n = Math.max(1, Math.min(limit, 50000));
        List<QcInspectionLog> logs = logMapper.selectList(
                query.toWrapper().orderByDesc(QcInspectionLog::getId).last("LIMIT " + n));
        return enrich(logs);
    }

    private List<AuditRow> enrich(List<QcInspectionLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }

        Set<Long> insIds = logs.stream().map(QcInspectionLog::getInspectionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcInspection> inspections = insIds.isEmpty() ? Map.of()
                : inspectionMapper.selectBatchIds(insIds).stream()
                .collect(Collectors.toMap(QcInspection::getId, Function.identity(), (a, b) -> a));

        Set<Long> batchIds = inspections.values().stream().map(QcInspection::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcBatch> batches = batchIds.isEmpty() ? Map.of()
                : batchMapper.selectBatchIds(batchIds).stream()
                .collect(Collectors.toMap(QcBatch::getId, Function.identity(), (a, b) -> a));

        Set<Long> productIds = inspections.values().stream().map(QcInspection::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcProduct> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(QcProduct::getId, Function.identity(), (a, b) -> a));

        List<AuditRow> rows = new ArrayList<>(logs.size());
        for (QcInspectionLog log : logs) {
            QcInspection ins = inspections.get(log.getInspectionId());
            QcBatch batch = ins == null ? null : batches.get(ins.getBatchId());
            QcProduct product = ins == null ? null : products.get(ins.getProductId());
            rows.add(new AuditRow(
                    log.getId(),
                    log.getAction(),
                    log.getInspectionId(),
                    ins == null ? null : ins.getInspectionNo(),
                    batch == null ? null : batch.getBatchNo(),
                    product == null ? null : product.getProductName(),
                    log.getFromStatus(),
                    log.getToStatus(),
                    log.getReason(),
                    log.getOperator(),
                    log.getSummary(),
                    log.getCreatedAt()));
        }
        return rows;
    }

    /**
     * 一条日志的展示形态。
     *
     * <p>{@code inspectionId} 保留下来是给前端「点一下跳回原单」用的——
     * 只看到单号却点不进去，查问题时还得手工复制粘贴。
     */
    public record AuditRow(Long id, String action, Long inspectionId, String inspectionNo,
                           String batchNo, String productName,
                           String fromStatus, String toStatus, String reason,
                           String operator, String summary, LocalDateTime createdAt) {
    }
}
