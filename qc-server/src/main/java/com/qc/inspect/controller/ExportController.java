package com.qc.inspect.controller;

import com.qc.inspect.dto.AuditQuery;
import com.qc.inspect.dto.ComplaintQuery;
import com.qc.inspect.dto.InspectionQuery;
import com.qc.inspect.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * CSV 导出。前端用 responseType=blob 接，浏览器直接下载。
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    /** Excel 打开 UTF-8 CSV 不加 BOM 会把中文读成乱码，这三个字节是让财务能直接双击的前提 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final ExportService exportService;

    /** 检验单台账。筛选条件与列表页共用 {@link InspectionQuery}，导出的行数和屏幕一致 */
    @GetMapping("/inspections")
    public ResponseEntity<byte[]> inspections(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) Long productId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String inspector,
                                              @RequestParam(required = false) String result,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return csv(exportService.inspectionLedger(
                new InspectionQuery(status, null, productId, keyword, inspector, result, from, to)),
                "inspections");
    }

    /** 不良台账 */
    @GetMapping("/defects")
    public ResponseEntity<byte[]> defects(@RequestParam(required = false) String status) {
        return csv(exportService.defectLedger(status), "defects");
    }

    /** 单张检验单的明细 */
    @GetMapping("/inspections/{id}/lines")
    public ResponseEntity<byte[]> lines(@PathVariable Long id) {
        return csv(exportService.inspectionLines(id), "inspection-" + id + "-lines");
    }

    /** 不良帕累托（带累计占比） */
    @GetMapping("/pareto")
    public ResponseEntity<byte[]> pareto(@RequestParam(required = false) Long productId,
                                         @RequestParam(required = false) String from) {
        return csv(exportService.pareto(productId, from), "pareto");
    }

    /** 近 30 天合格率（单级 + 件级两个口径） */
    @GetMapping("/daily-yield")
    public ResponseEntity<byte[]> dailyYield() {
        return csv(exportService.dailyYield(), "daily-yield");
    }

    /** 操作日志，条件与列表页共用 {@link AuditQuery}。内审要的就是这份「谁动过单」 */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> auditLogs(@RequestParam(required = false) String action,
                                            @RequestParam(required = false) String operator,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return csv(exportService.auditLedger(new AuditQuery(action, operator, keyword, from, to)),
                "audit-logs");
    }

    /** 客户投诉台账，条件与列表页共用 {@link ComplaintQuery}。体系审核每年必打这一份 */
    @GetMapping("/complaints")
    public ResponseEntity<byte[]> complaints(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) Boolean open) {
        return csv(exportService.complaintLedger(
                new ComplaintQuery(status, severity, keyword, from, to, open)), "complaints");
    }

    private ResponseEntity<byte[]> csv(String content, String name) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);

        String fileName = name + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(withBom);
    }
}
