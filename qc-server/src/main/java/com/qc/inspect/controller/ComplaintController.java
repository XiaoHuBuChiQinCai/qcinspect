package com.qc.inspect.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.dto.ComplaintQuery;
import com.qc.inspect.entity.QcComplaint;
import com.qc.inspect.service.ComplaintService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 客户投诉。
 *
 * <p>登记对所有登录用户开放：客诉往往是销售、客服、业务先接到的，
 * 只有质检能录的话，消息会烂在微信里三天。
 * 关闭则要求 ADMIN 或 REVIEWER——「问题解决了」这句话不能由接电话的人自己说。
 */
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;

    /** 客诉分页。keyword 同时匹配客诉单号、客户、批次号 */
    @GetMapping("/page")
    public R<Page<QcComplaint>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Boolean open) {
        return R.ok(complaintService.page(
                new ComplaintQuery(status, severity, keyword, from, to, open), current, size));
    }

    /** 某批次的客诉，追溯页嵌入式展示用 */
    @GetMapping
    public R<List<QcComplaint>> byBatch(@RequestParam Long batchId) {
        return R.ok(complaintService.byBatch(batchId));
    }

    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(complaintService.summary());
    }

    /** 登记客诉。批次必填，客户与问题描述必填 */
    @PostMapping
    public R<QcComplaint> create(@RequestBody CreateReq req) {
        return R.ok(complaintService.create(req.batchId(), req.shipmentId(), req.customer(),
                req.complaintDate(), req.category(), req.severity(), req.qty(),
                req.description(), req.remark(), UserContext.username()));
    }

    /** 受理：待处理 → 处理中 */
    @PostMapping("/{id}/start")
    public R<QcComplaint> start(@PathVariable Long id) {
        return R.ok(complaintService.start(id));
    }

    /** 关闭：根因、措施、责任方三项必填 */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    public R<QcComplaint> close(@PathVariable Long id, @RequestBody CloseReq req) {
        return R.ok(complaintService.close(id, req.rootCause(), req.action(),
                req.responsibility(), UserContext.username()));
    }

    /** category：APP/SIZE/PERF/PACK/MARK/OTHER；severity：HIGH/MEDIUM/LOW */
    public record CreateReq(Long batchId, Long shipmentId, String customer, LocalDate complaintDate,
                            String category, String severity, Integer qty,
                            String description, String remark) {
    }

    /** 关闭请求体：三项都必填 */
    public record CloseReq(String rootCause, String action, String responsibility) {
    }
}
