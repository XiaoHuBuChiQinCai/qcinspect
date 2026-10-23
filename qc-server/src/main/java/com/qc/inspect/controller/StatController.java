package com.qc.inspect.controller;

import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.service.CapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 质量统计。目前只有过程能力，后续趋势类指标也放这里。
 *
 * <p>日期收成 String 再自己 parse，和导出接口保持一致——
 * 直接用 LocalDate 当 @RequestParam 时，绑定格式取决于 JVM 区域设置，
 * 换个 locale 就可能从 yyyy-MM-dd 变成别的写法，属于换台机器就崩的那种坑。
 */
@RestController
@RequestMapping("/api/stat")
@RequiredArgsConstructor
public class StatController {

    private final CapabilityService capabilityService;

    /**
     * 有数值实测值的「产品 + 检验项」组合，供页面下拉选择。
     *
     * <p>只返回真能算的组合：没有 NUMBER 实测值的检验项不出现，
     * 免得上拉框里一堆选了没结果的选项。
     */
    @GetMapping("/capability/items")
    public R<List<Map<String, Object>>> items() {
        return R.ok(capabilityService.items());
    }

    /** 过程能力分析：Cp/Cpk/Pp/Ppk + 超规数 + 直方图 + I-MR 控制图 */
    @GetMapping("/capability")
    public R<Map<String, Object>> capability(@RequestParam Long productId,
                                             @RequestParam Long itemId,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        return R.ok(capabilityService.analyze(productId, itemId, parseDate(from), parseDate(to)));
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of("日期格式不对，要 yyyy-MM-dd：" + s);
        }
    }
}
