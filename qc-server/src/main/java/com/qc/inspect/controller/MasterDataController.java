package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcItem;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcItemMapper;
import com.qc.inspect.mapper.QcProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主数据：产品、检验项、批次。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MasterDataController {

    private final QcProductMapper productMapper;
    private final QcItemMapper itemMapper;
    private final QcBatchMapper batchMapper;

    // ---------------- 产品 ----------------

    @GetMapping("/products")
    public R<List<QcProduct>> products(@RequestParam(required = false) String keyword) {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<QcProduct>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(QcProduct::getProductCode, keyword)
                        .or().like(QcProduct::getProductName, keyword))
                .orderByDesc(QcProduct::getId)));
    }

    @PostMapping("/products")
    public R<QcProduct> saveProduct(@RequestBody QcProduct product) {
        if (product.getProductCode() == null || product.getProductCode().isBlank()) {
            throw BizException.of("产品编号不能为空");
        }
        if (product.getId() == null) {
            product.setCreatedAt(LocalDateTime.now());
            productMapper.insert(product);
        } else {
            product.setUpdatedAt(LocalDateTime.now());
            productMapper.updateById(product);
        }
        return R.ok(product);
    }

    // ---------------- 检验项 ----------------

    @GetMapping("/items")
    public R<List<QcItem>> items(@RequestParam(required = false) String groupName) {
        return R.ok(itemMapper.selectList(new LambdaQueryWrapper<QcItem>()
                .eq(groupName != null && !groupName.isBlank(), QcItem::getGroupName, groupName)
                .orderByAsc(QcItem::getGroupName)
                .orderByAsc(QcItem::getId)));
    }

    @PostMapping("/items")
    public R<QcItem> saveItem(@RequestBody QcItem item) {
        if (item.getItemCode() == null || item.getItemCode().isBlank()) {
            throw BizException.of("检验项编号不能为空");
        }
        if (item.getValueType() == null) {
            throw BizException.of("必须指定数据类型");
        }
        if (item.getId() == null) {
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);
        } else {
            item.setUpdatedAt(LocalDateTime.now());
            itemMapper.updateById(item);
        }
        return R.ok(item);
    }

    // ---------------- 批次 ----------------

    @GetMapping("/batches")
    public R<List<QcBatch>> batches(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long productId) {
        return R.ok(batchMapper.selectList(new LambdaQueryWrapper<QcBatch>()
                .eq(status != null && !status.isBlank(), QcBatch::getStatus, status)
                .eq(productId != null, QcBatch::getProductId, productId)
                .orderByDesc(QcBatch::getId)));
    }

    @PostMapping("/batches")
    public R<QcBatch> saveBatch(@RequestBody QcBatch batch) {
        if (batch.getProductId() == null) {
            throw BizException.of("必须指定产品");
        }
        if (batch.getQty() == null || batch.getQty() <= 0) {
            throw BizException.of("批次数量必须大于 0");
        }
        if (batch.getId() == null) {
            if (batch.getStatus() == null) {
                batch.setStatus("PENDING");
            }
            batch.setCreatedAt(LocalDateTime.now());
            batchMapper.insert(batch);
        } else {
            batch.setUpdatedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
        }
        return R.ok(batch);
    }
}
