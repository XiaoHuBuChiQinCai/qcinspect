package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.entity.QcShipment;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcComplaintMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcProductMapper;
import com.qc.inspect.mapper.QcShipmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 出货登记、出货前校验与红冲。
 *
 * <p>为什么出货要拦一道：{@code qc_shipment} 是追溯链的终点，客诉来了靠它反查
 * 「这批货发给了谁」。如果登记环节什么都不校验，就会出现「不合格批次照常出货」
 * 和「超发」——两个都是质量体系里最难看的问题，而且事后无法从数据上恢复。
 *
 * <p>校验尺度是「默认关门，允许显式开」：不合格批次、还没结论的批次都能出，
 * 但必须调用方明确传 force。默默放行和一刀切禁止都不对——
 * 前者是质量事故的温床，后者会让车间绕过系统手工记账。
 *
 * <p>登记错了怎么办：见 {@link #voidShipment}。作废而不是删除，因为
 * 「这单曾经存在过、后来被谁以什么理由销掉」本身就是质量记录的一部分。
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final QcBatchMapper batchMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcShipmentMapper shipmentMapper;
    private final QcProductMapper productMapper;
    private final QcComplaintMapper complaintMapper;

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
        m.put("openComplaints", complaintMapper.countOpen(batchId));
        m.put("blocking", blockingReason(latest, batchId));
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
        String blocking = blockingReason(latest, batchId);
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
        s.setStatus(QcShipment.STATUS_NORMAL);
        s.setCreatedBy(operator);
        s.setCreatedAt(LocalDateTime.now());
        shipmentMapper.insert(s);
        return s;
    }

    /**
     * 出货记录分页。
     *
     * <p>关键字同时匹配出货单号、客户、批次号：客诉电话里客户报的多半是自己那批货的
     * 批次号或者「上个月发的那三件」，不会报出货单号。
     */
    public Page<QcShipment> page(long current, long size, String status, String keyword,
                                 String from, String to) {
        String kw = blankToNull(keyword);
        String dayFrom = blankToNull(from);
        String dayTo = blankToNull(to);

        LambdaQueryWrapper<QcShipment> w = new LambdaQueryWrapper<QcShipment>()
                .eq(!isBlank(status), QcShipment::getStatus, status)
                .and(kw != null, q -> q
                        .like(QcShipment::getShipmentNo, kw)
                        .or()
                        .like(QcShipment::getCustomer, kw)
                        // 批次号不是本表列，用 EXISTS；占位符必须 CAST 成 text，
                        // 否则 PostgreSQL 的 CONCAT 推断不出参数类型，接口 500
                        .or()
                        .apply("EXISTS (SELECT 1 FROM qc_batch b WHERE b.id = qc_shipment.batch_id"
                                + " AND b.batch_no LIKE CONCAT('%', CAST({0} AS text), '%'))", kw))
                .apply(dayFrom != null, "shipped_at >= CAST({0} AS date)", dayFrom)
                .apply(dayTo != null, "shipped_at < CAST({0} AS date) + interval '1 day'", dayTo)
                .orderByDesc(QcShipment::getShippedAt)
                .orderByDesc(QcShipment::getId);

        Page<QcShipment> page = new Page<>(current, size);
        shipmentMapper.selectPage(page, w);
        fill(page.getRecords());
        return page;
    }

    /**
     * 作废（红冲）出货单。
     *
     * <p>门槛有三道，缺一不可：
     * <ol>
     *   <li>理由必填——作废是抹掉一条出货事实，没有理由的记录一年后没人看得懂</li>
     *   <li>只能销自己的单，管理员例外。谁登记谁负责，不然互相销账就查不清了</li>
     *   <li>不能重复作废。这里用「带 status 条件的更新」而不是先查后改，
     *       两个人同时点作废时只有一个能更新成功，另一个会拿到明确提示</li>
     * </ol>
     */
    public QcShipment voidShipment(Long id, String reason, String operator, boolean admin) {
        QcShipment s = id == null ? null : shipmentMapper.selectById(id);
        if (s == null) {
            throw BizException.of("出货单不存在，可能已被删除");
        }
        if (QcShipment.STATUS_VOID.equals(s.getStatus())) {
            throw BizException.of("该出货单已作废，不需要重复作废");
        }
        if (isBlank(reason)) {
            throw BizException.of("作废必须写理由。客户退货、登记错数量、重复登记都要写清楚");
        }
        boolean owner = s.getCreatedBy() != null && s.getCreatedBy().equals(operator);
        if (!admin && !owner) {
            throw BizException.of("只能作废自己登记的出货单（登记人：" 
                    + (s.getCreatedBy() == null ? "未知" : s.getCreatedBy()) + "），需要管理员操作");
        }

        int updated = shipmentMapper.update(null, new LambdaUpdateWrapper<QcShipment>()
                .eq(QcShipment::getId, id)
                .eq(QcShipment::getStatus, QcShipment.STATUS_NORMAL)
                .set(QcShipment::getStatus, QcShipment.STATUS_VOID)
                .set(QcShipment::getVoidReason, reason.trim())
                .set(QcShipment::getVoidedBy, operator)
                .set(QcShipment::getVoidedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw BizException.of("该出货单刚被其他人作废，请刷新后再看");
        }

        QcShipment fresh = shipmentMapper.selectById(id);
        fill(List.of(fresh));
        return fresh;
    }

    /** 补齐批次号与产品名。列表二十行原本会变成四十次查询，这里一次批量取回 */
    private void fill(List<QcShipment> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> batchIds = rows.stream().map(QcShipment::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcBatch> batches = batchIds.isEmpty() ? Map.of()
                : batchMapper.selectBatchIds(batchIds).stream()
                .collect(Collectors.toMap(QcBatch::getId, Function.identity(), (a, b) -> a));
        Set<Long> productIds = batches.values().stream().map(QcBatch::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcProduct> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(QcProduct::getId, Function.identity(), (a, b) -> a));

        for (QcShipment row : rows) {
            QcBatch batch = batches.get(row.getBatchId());
            row.setBatchNo(batch == null ? null : batch.getBatchNo());
            QcProduct product = batch == null || batch.getProductId() == null
                    ? null : products.get(batch.getProductId());
            row.setProductName(product == null ? null : product.getProductName());
        }
    }

    /**
     * 放行判断。返回 null 表示可以直接出货，返回字符串表示拦下的原因（多条用分号连）。
     *
     * <p>两个来源合起来看：
     * <ol>
     *   <li><b>检验结论</b>——只看最新一张已提交/已归档的检验单。批次可能返工后重检，
     *       早期那张 FAIL 已经被后续 PASS 覆盖，不该继续拦着</li>
     *   <li><b>未关闭客诉</b>——客户已经投诉了这批货、问题还没查清楚，又接着发给下一个客户，
     *       等于把同一个问题复制出去。内部不良品有 qc_defect 闭环拦着，客户侧同样得拦</li>
     * </ol>
     */
    private String blockingReason(QcInspection latest, Long batchId) {
        List<String> blocks = new ArrayList<>();
        if (latest == null) {
            blocks.add("该批次还没有任何检验结论");
        } else if ("FAIL".equals(latest.getResult())) {
            blocks.add("该批次最新检验结论为不合格（" + latest.getInspectionNo() + "）");
        } else if ("NA".equals(latest.getResult())) {
            blocks.add("该批次检验单尚未判定（" + latest.getInspectionNo() + "）");
        }

        long openComplaints = complaintMapper.countOpen(batchId);
        if (openComplaints > 0) {
            blocks.add("该批次有 " + openComplaints + " 条未关闭的客户投诉");
        }
        return blocks.isEmpty() ? null : String.join("；", blocks);
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
