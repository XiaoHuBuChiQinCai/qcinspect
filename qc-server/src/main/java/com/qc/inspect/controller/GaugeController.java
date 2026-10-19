package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.service.GaugeService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

@RestController
@RequestMapping("/api/gauges")
@RequiredArgsConstructor
public class GaugeController {

    private final GaugeService gaugeService;

    @GetMapping
    public R<List<QcGauge>> list(@RequestParam(required = false) String status) {
        return R.ok(gaugeService.list(status));
    }

    /** 临近到期 / 已过期，days 默认 30 */
    @GetMapping("/expiring")
    public R<List<QcGauge>> expiring(@RequestParam(defaultValue = "30") int days) {
        return R.ok(gaugeService.expiring(days));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcGauge> save(@RequestBody QcGauge gauge) {
        return R.ok(gaugeService.save(gauge));
    }

    /** 送检后登记新校准日期 */
    @PostMapping("/{id}/calibrate")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcGauge> calibrate(@PathVariable Long id,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate calibrateDate,
                                @RequestParam(required = false) Integer cycleDays) {
        return R.ok(gaugeService.calibrate(id, calibrateDate, cycleDays));
    }

    @PostMapping("/{id}/scrap")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> scrap(@PathVariable Long id, @RequestParam(required = false) String reason) {
        gaugeService.scrap(id, reason);
        return R.ok();
    }
}
