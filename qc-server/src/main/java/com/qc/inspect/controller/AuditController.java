package com.qc.inspect.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.common.R;
import com.qc.inspect.dto.AuditQuery;
import com.qc.inspect.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志检索。
 *
 * <p>只给管理员：这些日志里有作废理由、退回意见，等于把质检的争议记录摊开给人看，
 * 不该是所有人都能随手翻的。单据自己的轨迹（{@code /inspections/{id}/logs}）不受此限。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Page<AuditService.AuditRow>> logs(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String action,
                                               @RequestParam(required = false) String operator,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        AuditQuery query = new AuditQuery(action, operator, keyword, from, to);
        return R.ok(auditService.page(query, current, size));
    }
}
