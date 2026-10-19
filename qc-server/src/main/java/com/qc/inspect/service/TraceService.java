package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.entity.QcShipment;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcProductMapper;
import com.qc.inspect.mapper.QcShipmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次追溯。
 *
 * <p>两个方向都要能走：
 * <ul>
 *   <li>正向：某个原料批 → 做了哪些检验 → 发给了哪些客户（出问题要召回）</li>
 *   <li>反向：客户投诉批号 → 哪张检验单 → 谁验的、用了哪把量具、当时的实测值（要定责）</li>
 * </ul>
 *
 * <p>能查到「用了哪把量具」这件事，是这套系统和 Excel 的分水岭。
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcDefectMapper defectMapper;
    private final QcShipmentMapper shipmentMapper;

    /** 按批次追溯 */
    public Map<String, Object> byBatch(Long batchId) {
        QcBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BizException.of("批次不存在：" + batchId);
        }
        return assemble(batch);
    }

    /** 按出货单号反向追溯 */
    public Map<String, Object> byShipment(String shipmentNo) {
        QcShipment shipment = shipmentMapper.selectOne(new LambdaQueryWrapper<QcShipment>()
                .eq(QcShipment::getShipmentNo, shipmentNo));
        if (shipment == null) {
            throw BizException.of("出货单不存在：" + shipmentNo);
        }
        Map<String, Object> result = byBatch(shipment.getBatchId());
        result.put("shipment", shipment);
        return result;
    }

    /** 按客户查：这个客户收到过哪些批次，各批的检验结论 */
    public List<Map<String, Object>> byCustomer(String customer) {
        List<QcShipment> shipments = shipmentMapper.selectList(new LambdaQueryWrapper<QcShipment>()
                .like(QcShipment::getCustomer, customer)
                .orderByDesc(QcShipment::getShippedAt));
        return shipments.stream().map(s -> {
            QcBatch b = batchMapper.selectById(s.getBatchId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shipment", s);
            m.put("batch", b);
            if (b != null) {
                m.put("inspections", inspectionsOf(b.getId()));
            }
            return m;
        }).toList();
    }

    private Map<String, Object> assemble(QcBatch batch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batch", batch);
        m.put("product", productMapper.selectById(batch.getProductId()));
        m.put("inspections", inspectionsOf(batch.getId()));
        m.put("shipments", shipmentMapper.selectList(new LambdaQueryWrapper<QcShipment>()
                .eq(QcShipment::getBatchId, batch.getId())
                .orderByDesc(QcShipment::getShippedAt)));
        return m;
    }

    /** 批次下的检验单 + 各自的不良明细 */
    private List<Map<String, Object>> inspectionsOf(Long batchId) {
        List<QcInspection> list = inspectionMapper.selectList(new LambdaQueryWrapper<QcInspection>()
                .eq(QcInspection::getBatchId, batchId)
                .orderByDesc(QcInspection::getCreatedAt));
        return list.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inspection", i);
            List<QcDefect> defects = defectMapper.byInspection(i.getId());
            m.put("defects", defects);
            m.put("openDefects", defects.stream()
                    .filter(d -> !"DONE".equals(d.getDispositionStatus())
                            && !"WAIVED".equals(d.getDispositionStatus()))
                    .count());
            return m;
        }).toList();
    }
}
