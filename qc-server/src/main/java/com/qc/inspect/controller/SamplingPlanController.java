package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.service.SamplingPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 抽样方案查询。只读——方案数据属于质量约定，改动走 SQL 脚本并留记录，
 * 不在页面上随手改，否则「凭什么抽这么多件」就查不清了。
 */
@RestController
@RequestMapping("/api/sampling-plans")
@RequiredArgsConstructor
public class SamplingPlanController {

    private final SamplingPlanService samplingPlanService;

    /** 方案表里现有哪些 AQL / 检验水平 / 方案组，配置页下拉框用 */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(samplingPlanService.options());
    }

    /**
     * @param aql   接收质量限，不传用默认 2.5
     * @param level 一般检验水平，不传用默认 II
     * @param lot   试算批量，传了就顺带算出该批量的字码 / n / Ac
     */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) BigDecimal aql,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(required = false) Integer lot) {
        return R.ok(samplingPlanService.preview(aql, level, lot));
    }
}
