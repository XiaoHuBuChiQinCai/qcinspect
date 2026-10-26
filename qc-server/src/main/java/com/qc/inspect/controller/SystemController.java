package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.service.BackupHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统自身的健康状况。
 *
 * <p>目前只有备份一件事。以后如果加磁盘、证书到期，也放这里——
 * 这些都是「管理员该知道、但和质检业务无关」的信息，不该混进检验单或报表接口。
 *
 * <p>只给 ADMIN：检验员看到「备份已 80 小时没有成功」什么都做不了，
 * 只会在心里多一条无法处理的事。这类灯必须亮在能关掉它的人面前。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final BackupHealthService backupHealthService;

    /**
     * 备份健康。
     *
     * @param recent 附带返回的最近执行条数，默认 10
     */
    @GetMapping("/backup-health")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> backupHealth(
            @RequestParam(required = false, defaultValue = "10") int recent) {
        return R.ok(backupHealthService.health(recent));
    }
}
