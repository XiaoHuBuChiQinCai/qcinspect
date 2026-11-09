package com.qc.inspect.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.common.R;
import com.qc.inspect.msa.MsaView;
import com.qc.inspect.service.MsaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 量具测量系统分析（GR&amp;R）。
 *
 * <p>录入对所有登录用户开放：做 GR&amp;R 是检验员在车间干的活，不需要先找管理员。
 * 作废的归属判断放在服务层——管理员或录入人本人，且必须写理由。
 */
@RestController
@RequestMapping("/api/msa")
@RequiredArgsConstructor
public class MsaController {

    private final MsaService msaService;

    @GetMapping("/page")
    public R<Page<MsaView>> page(@RequestParam(defaultValue = "1") long current,
                                 @RequestParam(defaultValue = "10") long size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) Long gaugeId,
                                 @RequestParam(required = false) String verdict) {
        return R.ok(msaService.page(current, size, keyword, gaugeId, verdict));
    }

    @GetMapping("/{id}")
    public R<MsaView> detail(@PathVariable Long id) {
        return R.ok(msaService.detail(id));
    }

    @PostMapping
    public R<MsaView> create(@RequestBody MsaService.CreateReq req) {
        return R.ok(msaService.create(req));
    }

    @PostMapping("/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable Long id, @RequestBody CancelReq req) {
        msaService.cancel(id, req.reason());
        return R.ok(Map.of("id", id, "cancelled", true));
    }

    public record CancelReq(String reason) {
    }
}
