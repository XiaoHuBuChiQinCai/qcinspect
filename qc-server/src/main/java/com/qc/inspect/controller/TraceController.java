package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcShipment;
import com.qc.inspect.mapper.QcShipmentMapper;
import com.qc.inspect.service.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 追溯与出货。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;
    private final QcShipmentMapper shipmentMapper;

    /** 正向追溯：批次 → 检验 → 客户 */
    @GetMapping("/trace/batch/{batchId}")
    public R<Map<String, Object>> byBatch(@PathVariable Long batchId) {
        return R.ok(traceService.byBatch(batchId));
    }

    /** 反向追溯：出货单号 → 批次 → 检验单 → 量具与实测值 */
    @GetMapping("/trace/shipment/{shipmentNo}")
    public R<Map<String, Object>> byShipment(@PathVariable String shipmentNo) {
        return R.ok(traceService.byShipment(shipmentNo));
    }

    /** 某个客户收到过的所有批次 */
    @GetMapping("/trace/customer")
    public R<List<Map<String, Object>>> byCustomer(@RequestParam String customer) {
        return R.ok(traceService.byCustomer(customer));
    }

    // ---------------- 出货记录 ----------------

    @GetMapping("/shipments")
    public R<List<QcShipment>> shipments(@RequestParam(required = false) Long batchId,
                                         @RequestParam(required = false) String customer) {
        return R.ok(shipmentMapper.selectList(new LambdaQueryWrapper<QcShipment>()
                .eq(batchId != null, QcShipment::getBatchId, batchId)
                .like(customer != null && !customer.isBlank(), QcShipment::getCustomer, customer)
                .orderByDesc(QcShipment::getShippedAt)));
    }

    @PostMapping("/shipments")
    public R<QcShipment> saveShipment(@RequestBody QcShipment shipment) {
        if (shipment.getBatchId() == null) {
            throw BizException.of("必须指定批次");
        }
        if (shipment.getCustomer() == null || shipment.getCustomer().isBlank()) {
            throw BizException.of("必须填写客户，否则追溯断了");
        }
        if (shipment.getQty() == null || shipment.getQty() <= 0) {
            throw BizException.of("出货数量必须大于 0");
        }
        if (shipment.getId() == null) {
            if (shipment.getShipmentNo() == null || shipment.getShipmentNo().isBlank()) {
                shipment.setShipmentNo("SH" + System.currentTimeMillis());
            }
            if (shipment.getShippedAt() == null) {
                shipment.setShippedAt(LocalDateTime.now());
            }
            shipment.setCreatedAt(LocalDateTime.now());
            shipmentMapper.insert(shipment);
        } else {
            shipmentMapper.updateById(shipment);
        }
        return R.ok(shipment);
    }
}
