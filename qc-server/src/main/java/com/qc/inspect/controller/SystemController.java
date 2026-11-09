package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.notify.NoticePushService;
import com.qc.inspect.service.BackupHealthService;
import com.qc.inspect.service.StorageHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统自身的健康状况。
 *
 * <p>目前有三件事：备份、磁盘容量、企业微信推送。以后如果加证书到期，也放这里——
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
    private final StorageHealthService storageHealthService;
    private final NoticePushService noticePushService;

    /**
     * 磁盘与容量。
     *
     * <p>只读接口，顺手会带上最大的几张表——「数据库占了 8 GB」这句话本身没有下一步动作，
     * 指出是哪张表在涨，才知道该清日志还是清历史单据。
     */
    @GetMapping("/storage-health")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> storageHealth() {
        return R.ok(storageHealthService.health());
    }

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

    /**
     * 企业微信推送健康。
     *
     * <p>返回的是「哪些通知没送到手机、为什么」，而不是一句「推送正常」。
     * 后者没人需要——推送正常的时候没人会打开这个接口。
     *
     * @param recent 附带返回的最近问题条数，默认 10
     */
    @GetMapping("/notice-push")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> noticePush(
            @RequestParam(required = false, defaultValue = "10") int recent) {
        return R.ok(noticePushService.health(recent));
    }

    /**
     * 手工重发。典型场景：管理员发现某人没绑账号，补上之后不想等下一次退回。
     *
     * <p>重发会重新解析接收人的账号，所以「把错绑的账号改对」之后重发是有效的。
     */
    @PostMapping("/notice-push/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> retryPush(@PathVariable Long id) {
        boolean queued = noticePushService.retry(id);
        return R.ok(Map.of(
                "queued", queued,
                "message", queued
                        ? "已重新入队，几秒后刷新看结果"
                        : "没发出去：接收人还没绑企业微信账号，或通道未启用"));
    }
}
