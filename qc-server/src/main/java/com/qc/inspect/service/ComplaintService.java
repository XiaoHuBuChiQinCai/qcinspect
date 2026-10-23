package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.common.BizException;
import com.qc.inspect.dto.ComplaintQuery;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcComplaint;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.entity.QcShipment;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcComplaintMapper;
import com.qc.inspect.mapper.QcProductMapper;
import com.qc.inspect.mapper.QcShipmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 客户投诉的登记与闭环。
 *
 * <p>这套系统原本的追溯链到「货发给了谁」就结束了，客户把不良品退回来的时候，
 * 系统里没有任何地方能记下这件事。于是客诉只活在电话和微信里，年底体系审核
 * 问「今年投诉几起、关闭率多少、根因分布」——一条都拿不出来。
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>批次必填</b>。没有批次的客诉无法定责、无法追溯，只能算抱怨。
 *       硬拦着让人先把批次找出来，是这套系统和一张 Excel 的区别</li>
 *   <li><b>根因/措施/责任方只在关闭时必填</b>。要求一登记就写根因，
 *       结果一定是「已处理」三个字，那比不填更糟</li>
 *   <li><b>关闭用带 status 条件的更新</b>。两个人同时点关闭时只有一个能成功，
 *       而不是互相覆盖对方的根因分析</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    /** 客诉类别白名单。写死在这里而不是建码表：类别是质量体系的固定用语，不该现场自造 */
    private static final Set<String> CATEGORIES =
            Set.of("APP", "SIZE", "PERF", "PACK", "MARK", "OTHER");

    /** 严重度白名单 */
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final QcComplaintMapper complaintMapper;
    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;
    private final QcShipmentMapper shipmentMapper;

    /**
     * 登记客诉。
     *
     * @param shipmentId 对应出货单，可空——客户常常说不清是哪一单
     * @param qty        客户反馈的涉及数量，可空——客户报不准时不要瞎填
     */
    public QcComplaint create(Long batchId, Long shipmentId, String customer, LocalDate complaintDate,
                              String category, String severity, Integer qty, String description,
                              String remark, String operator) {
        QcBatch batch = batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null) {
            throw BizException.of("必须选择被投诉的批次。没有批次的客诉无法追溯，也没法定责");
        }
        if (isBlank(customer)) {
            throw BizException.of("必须填写客户名称");
        }
        if (isBlank(description)) {
            throw BizException.of("必须描述客户反馈的问题，否则后续无法分析根因");
        }
        LocalDate day = complaintDate == null ? LocalDate.now() : complaintDate;
        if (day.isAfter(LocalDate.now())) {
            throw BizException.of("客诉日期不能晚于今天");
        }
        if (qty != null && qty <= 0) {
            throw BizException.of("涉及数量必须大于 0，报不准就留空");
        }

        String cat = normalize(category, CATEGORIES, "OTHER");
        String sev = normalize(severity, SEVERITIES, "MEDIUM");

        if (shipmentId != null) {
            QcShipment s = shipmentMapper.selectById(shipmentId);
            if (s == null) {
                throw BizException.of("出货单不存在，无法关联");
            }
            if (!Objects.equals(s.getBatchId(), batchId)) {
                // 关联错了比不关联更糟：以后按出货单核算客诉会算到别的批次头上
                throw BizException.of("该出货单（" + s.getShipmentNo() + "）不属于所选批次，请重新选择");
            }
        }

        QcComplaint c = new QcComplaint();
        c.setComplaintNo(nextNo());
        c.setBatchId(batchId);
        c.setShipmentId(shipmentId);
        c.setCustomer(customer.trim());
        c.setComplaintDate(day);
        c.setCategory(cat);
        c.setSeverity(sev);
        c.setQty(qty);
        c.setDescription(description.trim());
        c.setStatus(QcComplaint.STATUS_OPEN);
        c.setRemark(blankToNull(remark));
        c.setCreatedBy(operator);
        c.setCreatedAt(LocalDateTime.now());
        complaintMapper.insert(c);
        fill(List.of(c));
        return c;
    }

    /** 从待处理转为处理中。已关闭的单不能再往回拖，否则关闭记录会失真 */
    public QcComplaint start(Long id) {
        QcComplaint c = require(id);
        if (QcComplaint.STATUS_CLOSED.equals(c.getStatus())) {
            throw BizException.of("该客诉已关闭，不能退回处理中");
        }
        if (QcComplaint.STATUS_PROCESSING.equals(c.getStatus())) {
            return c;
        }
        int n = complaintMapper.update(null, new LambdaUpdateWrapper<QcComplaint>()
                .eq(QcComplaint::getId, id)
                .eq(QcComplaint::getStatus, QcComplaint.STATUS_OPEN)
                .set(QcComplaint::getStatus, QcComplaint.STATUS_PROCESSING));
        if (n == 0) {
            throw BizException.of("该客诉状态刚被其他人改动，请刷新后再操作");
        }
        QcComplaint fresh = complaintMapper.selectById(id);
        fill(List.of(fresh));
        return fresh;
    }

    /**
     * 关闭客诉。
     *
     * <p>三项必填是硬门槛：根因、纠正预防措施、责任归属。缺任何一项就不叫闭环——
     * 「已和客户沟通，客户接受」这种记录在第二年不会有任何人看得懂。
     */
    public QcComplaint close(Long id, String rootCause, String action, String responsibility,
                             String operator) {
        QcComplaint c = require(id);
        if (QcComplaint.STATUS_CLOSED.equals(c.getStatus())) {
            throw BizException.of("该客诉已关闭，不需要重复关闭");
        }
        if (isBlank(rootCause)) {
            throw BizException.of("关闭前必须填写根本原因");
        }
        if (isBlank(action)) {
            throw BizException.of("关闭前必须填写纠正预防措施");
        }
        if (isBlank(responsibility)) {
            throw BizException.of("关闭前必须填写责任归属（供应商 / 本厂制程 / 设计 / 运输 / 客户方）");
        }

        int n = complaintMapper.update(null, new LambdaUpdateWrapper<QcComplaint>()
                .eq(QcComplaint::getId, id)
                .ne(QcComplaint::getStatus, QcComplaint.STATUS_CLOSED)
                .set(QcComplaint::getStatus, QcComplaint.STATUS_CLOSED)
                .set(QcComplaint::getRootCause, rootCause.trim())
                .set(QcComplaint::getAction, action.trim())
                .set(QcComplaint::getResponsibility, responsibility.trim())
                .set(QcComplaint::getClosedBy, operator)
                .set(QcComplaint::getClosedAt, LocalDateTime.now()));
        if (n == 0) {
            throw BizException.of("该客诉刚被其他人关闭，请刷新后再看");
        }
        QcComplaint fresh = complaintMapper.selectById(id);
        fill(List.of(fresh));
        return fresh;
    }

    /** 客诉分页 */
    public Page<QcComplaint> page(ComplaintQuery query, long current, long size) {
        Page<QcComplaint> p = new Page<>(current, size);
        complaintMapper.selectPage(p, query.toWrapper()
                .orderByDesc(QcComplaint::getComplaintDate)
                .orderByDesc(QcComplaint::getId));
        fill(p.getRecords());
        return p;
    }

    /** 导出用：不分页，但给上限，免得有人点导出把内存打满 */
    public List<QcComplaint> list(ComplaintQuery query, int limit) {
        int n = Math.max(1, Math.min(limit, 50000));
        List<QcComplaint> rows = complaintMapper.selectList(query.toWrapper()
                .orderByDesc(QcComplaint::getComplaintDate)
                .orderByDesc(QcComplaint::getId)
                .last("LIMIT " + n));
        fill(rows);
        return rows;
    }

    /** 某批次的全部客诉，追溯页用 */
    public List<QcComplaint> byBatch(Long batchId) {
        List<QcComplaint> rows = complaintMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QcComplaint>()
                        .eq(QcComplaint::getBatchId, batchId)
                        .orderByDesc(QcComplaint::getComplaintDate));
        fill(rows);
        return rows;
    }

    /** 未关闭客诉数，工作台与出货拦截共用 */
    public long countOpen(Long batchId) {
        return batchId == null ? 0 : complaintMapper.countOpen(batchId);
    }

    /** 严重度/关闭情况概览 */
    public Map<String, Object> summary() {
        return complaintMapper.summary();
    }

    /** 补齐批次号、产品名、出货单号。列表二十行原本会变成六十次查询，这里一次批量取回 */
    private void fill(List<QcComplaint> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> batchIds = rows.stream().map(QcComplaint::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcBatch> batches = batchIds.isEmpty() ? Map.of()
                : batchMapper.selectBatchIds(batchIds).stream()
                .collect(Collectors.toMap(QcBatch::getId, Function.identity(), (a, b) -> a));

        Set<Long> productIds = batches.values().stream().map(QcBatch::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcProduct> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(QcProduct::getId, Function.identity(), (a, b) -> a));

        Set<Long> shipmentIds = rows.stream().map(QcComplaint::getShipmentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcShipment> shipments = shipmentIds.isEmpty() ? Map.of()
                : shipmentMapper.selectBatchIds(shipmentIds).stream()
                .collect(Collectors.toMap(QcShipment::getId, Function.identity(), (a, b) -> a));

        for (QcComplaint row : rows) {
            QcBatch batch = batches.get(row.getBatchId());
            row.setBatchNo(batch == null ? null : batch.getBatchNo());
            QcProduct product = batch == null || batch.getProductId() == null
                    ? null : products.get(batch.getProductId());
            row.setProductName(product == null ? null : product.getProductName());
            QcShipment shipment = row.getShipmentId() == null ? null : shipments.get(row.getShipmentId());
            row.setShipmentNo(shipment == null ? null : shipment.getShipmentNo());
        }
    }

    private QcComplaint require(Long id) {
        QcComplaint c = id == null ? null : complaintMapper.selectById(id);
        if (c == null) {
            throw BizException.of("客诉记录不存在，可能已被删除");
        }
        return c;
    }

    private String nextNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long count = complaintMapper.countByDayPrefix(day);
        return "TS" + day + String.format("%03d", count + 1);
    }

    private static String normalize(String value, Set<String> allowed, String fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        String v = value.trim().toUpperCase();
        if (!allowed.contains(v)) {
            throw BizException.of("取值不合法：" + value + "，只能是 " + String.join(" / ", sorted(allowed)));
        }
        return v;
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        list.sort(String::compareTo);
        return list;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
