package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.service.MonthlyReportService;
import com.qc.inspect.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final MonthlyReportService monthlyReportService;

    /** 检验报告 / 合格证数据。前端渲染成 A4 页后打印或另存 PDF */
    @GetMapping("/inspection/{id}")
    public R<Map<String, Object>> inspection(@PathVariable Long id) {
        return R.ok(reportService.inspectionReport(id));
    }

    /**
     * 质量月报：一个月的合格率、主要不良、供应商、客诉、量具、返工汇总 + 环比。
     *
     * @param month yyyy-MM，留空取上月
     */
    @GetMapping("/monthly")
    public R<Map<String, Object>> monthly(@RequestParam(required = false) String month) {
        return R.ok(monthlyReportService.monthly(month));
    }
}
