package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcDefectCode;
import com.qc.inspect.mapper.QcDefectCodeMapper;
import com.qc.inspect.service.DefectService;
import com.qc.inspect.service.InspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DefectController {

    private final DefectService defectService;
    private final InspectionService inspectionService;
    private final QcDefectCodeMapper defectCodeMapper;

    // ---------------- 不良记录 ----------------

    @GetMapping("/defects")
    public R<List<QcDefect>> list(@RequestParam(required = false) Long inspectionId,
                                  @RequestParam(required = false) String status) {
        return R.ok(defectService.list(inspectionId, status));
    }

    /** 提交处置：REWORK / SCRAP / RETURN，status 可选 PROCESSING / DONE */
    @PostMapping("/defects/{id}/dispose")
    public R<QcDefect> dispose(@PathVariable Long id, @RequestBody DisposeReq req) {
        return R.ok(defectService.dispose(id, req.disposition(), req.status(), req.note(), UserContext.operator(req.operator())));
    }

    /** 让步接收审批：理由与授权人必填 */
    @PostMapping("/defects/{id}/concession")
    public R<QcDefect> concession(@PathVariable Long id, @RequestBody ConcessionReq req) {
        return R.ok(defectService.concession(id, req.reason(), req.approvedBy()));
    }

    /** 挂已有的复检单（补录用；正常流程请用下面的「开复检单」） */
    @PostMapping("/defects/{id}/recheck")
    public R<QcDefect> recheck(@PathVariable Long id, @RequestBody RecheckReq req) {
        return R.ok(defectService.linkRecheck(id, req.recheckInspectionId()));
    }

    /**
     * 开复检单：按这条不良的返工件数自动建一张复检单并回填关联。
     *
     * <p>与 {@code POST /defects/{id}/recheck} 的区别：那个是「我手上已经有一张单，把它挂上」，
     * 这个是「现在就给我开一张」。返工完成后检验员该走的是这一条。
     */
    @PostMapping("/defects/{id}/recheck/open")
    public R<Map<String, Object>> openRecheck(@PathVariable Long id) {
        return R.ok(inspectionService.createRecheck(id, UserContext.operator(null)));
    }

    /** 不良帕累托 */
    @GetMapping("/defects/pareto")
    public R<List<Map<String, Object>>> pareto(@RequestParam(required = false) Long productId,
                                               @RequestParam(required = false) String from) {
        return R.ok(defectService.pareto(productId, from));
    }

    // ---------------- 不良代码字典 ----------------

    @GetMapping("/defect-codes")
    public R<List<QcDefectCode>> codes(@RequestParam(required = false) String category) {
        return R.ok(defectCodeMapper.selectList(new LambdaQueryWrapper<QcDefectCode>()
                .eq(category != null && !category.isBlank(), QcDefectCode::getCategory, category)
                .orderByAsc(QcDefectCode::getCategory)
                .orderByAsc(QcDefectCode::getCode)));
    }

    @PostMapping("/defect-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcDefectCode> saveCode(@RequestBody QcDefectCode code) {
        if (code.getCode() == null || code.getCode().isBlank()) {
            throw BizException.of("不良代码不能为空");
        }
        if (code.getName() == null || code.getName().isBlank()) {
            throw BizException.of("不良名称不能为空");
        }
        if (code.getId() == null) {
            code.setCreatedAt(LocalDateTime.now());
            defectCodeMapper.insert(code);
        } else {
            defectCodeMapper.updateById(code);
        }
        return R.ok(code);
    }

    // ---------------- 请求体 ----------------

    public record DisposeReq(String disposition, String status, String note, String operator) {
    }

    public record ConcessionReq(String reason, String approvedBy) {
    }

    public record RecheckReq(Long recheckInspectionId) {
    }
}
