package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcShipment;
import com.qc.inspect.mapper.QcShipmentMapper;
import com.qc.inspect.service.ShipmentService;
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
    private final ShipmentService shipmentService;

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
    public R<QcShipment> saveShipment(@RequestBody ShipmentReq req,
                                     @RequestParam(required = false, defaultValue = "system") String operator) {
        return R.ok(shipmentService.create(req.batchId(), req.customer(), req.qty(), req.shippedAt(),
                req.remark(), Boolean.TRUE.equals(req.force()), UserContext.operator(operator)));
    }

    /** 出货前的前置信息：批次还剩多少可发、最新检验结论是什么、会不会被拦 */
    @GetMapping("/shipments/context/{batchId}")
    public R<Map<String, Object>> shipmentContext(@PathVariable Long batchId) {
        return R.ok(shipmentService.context(batchId));
    }

    /** 出货记录分页。关键字同时匹配出货单号、客户、批次号 */
    @GetMapping("/shipments/page")
    public R<Page<QcShipment>> shipmentPage(@RequestParam(defaultValue = "1") long current,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return R.ok(shipmentService.page(current, size, status, keyword, from, to));
    }

    /**
     * 作废出货单（红冲）。理由必填；非管理员只能销自己登记的单。
     *
     * <p>为什么要红冲而不是删除：客诉排查时，「这批货本来要发、后来客户取消」
     * 和「压根没发过」是两件完全不同的事，删掉就再也分不清了。
     */
    @PostMapping("/shipments/{id}/void")
    public R<QcShipment> voidShipment(@PathVariable Long id, @RequestBody VoidReq req,
                                      @RequestParam(required = false, defaultValue = "system") String operator) {
        return R.ok(shipmentService.voidShipment(id, req.reason(),
                UserContext.operator(operator), UserContext.isAdmin()));
    }

    /** 出货请求体。force=true 表示确认「例外放行」（不合格 / 无结论的批次） */
    public record ShipmentReq(Long batchId, String customer, Integer qty,
                              LocalDateTime shippedAt, String remark, Boolean force) {
    }

    /** 作废请求体：reason 必填 */
    public record VoidReq(String reason) {
    }
}
