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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/standards")
@RequiredArgsConstructor
public class StandardController {

    private final QcStandardMapper standardMapper;
    private final QcStandardItemMapper standardItemMapper;
    private final StandardService standardService;

    /** 某产品的所有标准版本，含已作废 */
    @GetMapping
    public R<List<QcStandard>> list(@RequestParam Long productId) {
        return R.ok(standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, productId)
                .orderByDesc(QcStandard::getVersion)));
    }

    /** 标准包含的检验项 */
    @GetMapping("/{id}/items")
    public R<List<QcStandardItem>> items(@PathVariable Long id) {
        return R.ok(standardItemMapper.selectList(new LambdaQueryWrapper<QcStandardItem>()
                .eq(QcStandardItem::getStandardId, id)
                .orderByAsc(QcStandardItem::getSeq)));
    }

    /** 新建草稿标准 */
    @PostMapping
    public R<QcStandard> create(@RequestBody QcStandard standard) {
        if (standard.getProductId() == null) {
            throw BizException.of("必须指定产品");
        }
        standard.setId(null);
        standard.setStatus("DRAFT");
        standard.setVersion(1);
        standard.setCreatedAt(LocalDateTime.now());
        standardMapper.insert(standard);
        return R.ok(standard);
    }

    /** 整体保存标准的检验项（先清后插，适合配置页一次性提交） */
    @PostMapping("/{id}/items")
    @Transactional
    public R<Integer> saveItems(@PathVariable Long id, @RequestBody List<QcStandardItem> items) {
        if (standardMapper.selectById(id) == null) {
            throw BizException.of("标准不存在：" + id);
        }
        if ("ACTIVE".equals(standardMapper.selectById(id).getStatus())) {
            throw BizException.of("生效中的标准不能直接改项，请发布新版本后再改");
        }
        standardItemMapper.delete(new LambdaQueryWrapper<QcStandardItem>()
                .eq(QcStandardItem::getStandardId, id));
        int seq = 0;
        for (QcStandardItem item : items) {
            item.setId(null);
            item.setStandardId(id);
            item.setSeq(seq += 10);
            item.setCreatedAt(LocalDateTime.now());
            standardItemMapper.insert(item);
        }
        return R.ok(items.size());
    }

    /** 以某版本为模板发布新版本（自动生效，旧版置作废） */
    @PostMapping("/{id}/publish")
    public R<QcStandard> publish(@PathVariable Long id) {
        return R.ok(standardService.publishNewVersion(id));
    }

    /** 启用草稿版本 */
    @PostMapping("/{id}/activate")
    public R<Void> activate(@PathVariable Long id) {
        standardService.activate(id);
        return R.ok();
    }
}
