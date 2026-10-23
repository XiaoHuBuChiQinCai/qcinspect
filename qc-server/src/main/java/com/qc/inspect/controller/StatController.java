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

    /**
     * 过程能力分析：Cp/Cpk/Pp/Ppk + 超规数 + 直方图 + I-MR 控制图。
     *
     * <p>两个 id 收成非必填再自己判：交给 Spring 的 required=true，漏参数时抛的是
     * 「Required request parameter 'itemId' for method parameter type Long is not present」，
     * 前端原样弹给用户就是一句英文天书。自己做能说清是哪个参数没给。
     */
    @GetMapping("/capability")
    public R<Map<String, Object>> capability(@RequestParam(required = false) Long productId,
                                             @RequestParam(required = false) Long itemId,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        if (productId == null || itemId == null) {
            throw BizException.of("请先选产品与检验项，过程能力要按「产品 + 检验项」算"
                    + "（同一个检验项用在不同产品上，规格限不一样，混着算等于拿两把尺子量同一批数）");
        }
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
