package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.dto.InspectionCreateReq;
import com.qc.inspect.dto.InspectionSubmitReq;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcInspectionLog;
import com.qc.inspect.judge.InspectionOutcome;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.service.InspectionService;
import com.qc.inspect.service.InspectionViewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;
    private final InspectionViewService viewService;
    private final QcInspectionMapper inspectionMapper;

    /** 建检验单：按批次 + 产品生效标准自动快照，并按抽样件数展开成 件 × 项 的明细 */
    @PostMapping
    public R<Map<String, Object>> create(@Valid @RequestBody InspectionCreateReq req,
                                         @RequestParam(required = false, defaultValue = "system") String operator) {
        return R.ok(inspectionService.create(req.batchId(), req.inspectorName(), req.sampleQty(), UserContext.operator(operator)));
    }

    /** 检验单详情，按件组织，每件内按分组归并 */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(inspectionService.detail(id));
    }

    /** 草稿暂存：只写实测值，不判定、不改状态 */
    @PostMapping("/{id}/save")
    public R<Void> save(@PathVariable Long id,
                        @RequestBody InspectionSubmitReq req,
                        @RequestParam(required = false, defaultValue = "system") String operator) {
        inspectionService.saveDraft(id, req.lines(), UserContext.operator(operator));
        return R.ok();
    }

    /** 提交检验结果并判定。提交后单据锁定，再改必须先 reopen */
    @PostMapping("/{id}/submit")
    public R<InspectionOutcome> submit(@PathVariable Long id,
                                       @RequestBody InspectionSubmitReq req,
                                       @RequestParam(required = false, defaultValue = "system") String operator) {
        return R.ok(inspectionService.submit(id, req.lines(), UserContext.operator(operator)));
    }

    /** 解锁已提交的检验单，必须给理由，会写日志 */
    @PostMapping("/{id}/reopen")
    public R<Void> reopen(@PathVariable Long id,
                          @RequestParam String reason,
                          @RequestParam(required = false, defaultValue = "system") String operator) {
        inspectionService.reopen(id, reason, UserContext.operator(operator));
        return R.ok();
    }

    /** 归档（审核员/管理员） */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public R<Void> close(@PathVariable Long id,
                         @RequestParam(required = false, defaultValue = "system") String operator) {
        inspectionService.close(id, UserContext.operator(operator));
        return R.ok();
    }

    /**
     * 审核：通过则归档，退回则回到草稿。
     *
     * <p>退回必须带意见；不能审核自己录入的单据（管理员除外）。
     */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public R<Void> review(@PathVariable Long id, @RequestBody ReviewReq req,
                          @RequestParam(required = false, defaultValue = "system") String operator) {
        inspectionService.review(id, Boolean.TRUE.equals(req.approve()), req.comment(),
                UserContext.operator(operator));
        return R.ok();
    }

    /** 作废，必须给理由，会写日志 */
    @PostMapping("/{id}/void")
    public R<Void> voidInspection(@PathVariable Long id,
                                  @RequestParam String reason,
                                  @RequestParam(required = false, defaultValue = "system") String operator) {
        inspectionService.voidInspection(id, reason, UserContext.operator(operator));
        return R.ok();
    }

    /** 操作日志，最新的在前 */
    @GetMapping("/{id}/logs")
    public R<List<QcInspectionLog>> logs(@PathVariable Long id) {
        return R.ok(inspectionService.logs(id));
    }

    /** 检验单分页。批次号/产品名不是表字段，这里一次性补齐，免得前端为一个列表发十次请求 */
    @GetMapping("/page")
    public R<Page<QcInspection>> page(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long batchId,
                                      @RequestParam(required = false) Long productId) {
        Page<QcInspection> page = new Page<>(current, size);
        inspectionMapper.selectPage(page, new LambdaQueryWrapper<QcInspection>()
                .eq(status != null && !status.isBlank(), QcInspection::getStatus, status)
                .eq(batchId != null, QcInspection::getBatchId, batchId)
                .eq(productId != null, QcInspection::getProductId, productId)
                .orderByDesc(QcInspection::getId));
        viewService.fill(page.getRecords());
        return R.ok(page);
    }

    /** 近 30 天合格率趋势 */
    @GetMapping("/stat/daily")
    public R<List<Map<String, Object>>> statDaily() {
        return R.ok(inspectionMapper.statDaily());
    }

    /** 缺陷帕累托数据 */
    @GetMapping("/stat/defect")
    public R<List<Map<String, Object>>> statDefect() {
        return R.ok(inspectionMapper.statDefect());
    }

    /** 审核请求体：approve=true 通过归档，false 退回草稿（comment 必填） */
    public record ReviewReq(Boolean approve, String comment) {
    }
}
