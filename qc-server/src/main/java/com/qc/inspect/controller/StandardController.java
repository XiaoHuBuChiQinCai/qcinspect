package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import com.qc.inspect.mapper.QcStandardItemMapper;
import com.qc.inspect.mapper.QcStandardMapper;
import com.qc.inspect.service.StandardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/standards")
@RequiredArgsConstructor
public class StandardController {

    private final QcStandardMapper standardMapper;
    private final QcStandardItemMapper standardItemMapper;
    private final StandardService standardService;

    /** 某产品的所有标准版本，含已作废 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam Long productId) {
        List<QcStandard> standards = standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, productId)
                .orderByDesc(QcStandard::getVersion));

        List<Map<String, Object>> rows = standards.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("standard", s);
            m.put("itemCount", standardItemMapper.selectCount(new LambdaQueryWrapper<QcStandardItem>()
                    .eq(QcStandardItem::getStandardId, s.getId())));
            return m;
        }).toList();
        return R.ok(rows);
    }

    /** 标准详情：表头 + 检验项 */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        QcStandard std = standardMapper.selectById(id);
        if (std == null) {
            throw BizException.of("标准不存在：" + id);
        }
        List<QcStandardItem> items = standardItemMapper.selectList(
                new LambdaQueryWrapper<QcStandardItem>()
                        .eq(QcStandardItem::getStandardId, id)
                        .orderByAsc(QcStandardItem::getSeq));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("standard", std);
        m.put("items", items);
        return R.ok(m);
    }

    /** 标准包含的检验项 */
    @GetMapping("/{id}/items")
    public R<List<QcStandardItem>> items(@PathVariable Long id) {
        return R.ok(standardItemMapper.selectList(new LambdaQueryWrapper<QcStandardItem>()
                .eq(QcStandardItem::getStandardId, id)
                .orderByAsc(QcStandardItem::getSeq)));
    }

    /**
     * 与上一版（或指定版本）的差异。
     *
     * <p>不带 {@code against} 就自动比上一版。质量问题追溯时问的是
     * 「这个标准最近改过什么、是不是放宽了」，不是「v2 和 v5 差在哪」——
     * 后者要人先记住版本号，前者才是日常。
     */
    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable Long id,
                                       @RequestParam(required = false) Long against) {
        return R.ok(standardService.diff(id, against));
    }

    /** 新建草稿标准 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcStandard> create(@RequestBody QcStandard standard) {
        return R.ok(standardService.createDraft(standard));
    }

    /** 修改草稿表头 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcStandard> update(@PathVariable Long id, @RequestBody QcStandard standard) {
        return R.ok(standardService.updateDraft(id, standard));
    }

    /** 删除草稿 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> delete(@PathVariable Long id) {
        standardService.deleteDraft(id);
        return R.ok();
    }

    /** 整体保存标准的检验项（先清后插，适合配置页一次性提交） */
    @PostMapping("/{id}/items")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Integer> saveItems(@PathVariable Long id, @RequestBody List<QcStandardItem> items) {
        return R.ok(standardService.saveItems(id, items));
    }

    /** 以某版本为模板发布新版本（自动生效，旧版置作废） */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcStandard> publish(@PathVariable Long id) {
        return R.ok(standardService.publishNewVersion(id));
    }

    /** 启用草稿版本 */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> activate(@PathVariable Long id) {
        standardService.activate(id);
        return R.ok();
    }
}
