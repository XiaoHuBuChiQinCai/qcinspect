package com.qc.inspect.controller;

import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcGaugeCalibration;
import com.qc.inspect.service.GaugeService;
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

    /**
     * 登记一次校准。日期、结果、证书号、校准机构一起落进履历——
     * 只记一个日期，外审问「上次是谁校的、证书几号」就答不上来。
     */
    @PostMapping("/{id}/calibrate")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcGauge> calibrate(@PathVariable Long id, @RequestBody CalibrateReq req) {
        return R.ok(gaugeService.calibrate(id, req.calibrateDate(), req.cycleDays(), req.result(),
                req.certificateNo(), req.agency(), req.remark(), UserContext.username()));
    }

    /** 校准履历，按校准日期倒序。任何登录用户都能查，现场核对时用得上 */
    @GetMapping("/{id}/calibrations")
    public R<List<QcGaugeCalibration>> calibrations(@PathVariable Long id) {
        return R.ok(gaugeService.calibrations(id));
    }

    @PostMapping("/{id}/scrap")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> scrap(@PathVariable Long id, @RequestParam(required = false) String reason) {
        gaugeService.scrap(id, reason);
        return R.ok();
    }

    /** result：PASS 合格 / FAIL 不合格 / LIMITED 限用，不传按 PASS */
    public record CalibrateReq(LocalDate calibrateDate, Integer cycleDays, String result,
                               String certificateNo, String agency, String remark) {
    }
}
