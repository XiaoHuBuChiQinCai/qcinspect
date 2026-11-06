package com.qc.inspect.controller;

import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcGaugeCalibration;
import com.qc.inspect.entity.QcGaugeCheck;
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

import java.math.BigDecimal;
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

    /**
     * 本月还没点检的量具。现场每月拿标准件对一遍，这一页就是催活用的。
     * 只含 ACTIVE 的——停用与报废的不催，催了也只会把人引去「再量一遍」。
     */
    @GetMapping("/checks/pending")
    public R<List<GaugeService.PendingCheck>> pendingChecks() {
        return R.ok(gaugeService.pendingChecks());
    }

    /** 某把量具的点检履历，按日期倒序 */
    @GetMapping("/{id}/checks")
    public R<List<QcGaugeCheck>> checks(@PathVariable Long id) {
        return R.ok(gaugeService.checks(id));
    }

    /**
     * 登记点检。谁在现场谁录，所以不设角色限制——
     * 但点检不合格会把量具置为停用，这个后果要在返回体里带出去，前端得当场告诉人。
     */
    @PostMapping("/{id}/check")
    public R<GaugeService.CheckResult> check(@PathVariable Long id, @RequestBody CheckReq req) {
        return R.ok(gaugeService.submitCheck(id, req.checkDate(), req.standardNo(),
                req.standardValue(), req.measuredValue(), req.tolerance(), req.remark(),
                UserContext.username()));
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

    /**
     * 点检入参：标准件标称值 + 允差 + 实测值，结论由后端算。
     * 不让前端传 result——前端算一遍后端再存一遍，两边算法一岔就成了「界面合格、库里不合格」。
     */
    public record CheckReq(LocalDate checkDate, String standardNo, BigDecimal standardValue,
                           BigDecimal measuredValue, BigDecimal tolerance, String remark) {
    }
}
