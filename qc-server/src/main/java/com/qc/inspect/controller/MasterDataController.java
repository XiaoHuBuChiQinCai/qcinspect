package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcItem;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.judge.EnumOption;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcItemMapper;
import com.qc.inspect.mapper.QcProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主数据：产品、检验项、批次。
 *
 * <p>这三张表是全系统的口径来源：产品决定用哪套标准，检验项决定怎么判定，批次决定检验什么。
 * 所以保存前一律先做前置校验——数据库上的唯一索引当然也会拦，但它抛的是
 * 「duplicate key value violates unique constraint "uk_batch_no"」，
 * 车间的人看不懂，只会当成系统坏了又去改回 Excel。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MasterDataController {

    private static final Set<String> INSPECT_TYPES = Set.of("IQC", "IPQC", "FQC", "OQC");
    private static final Set<String> VALUE_TYPES = Set.of("NUMBER", "ENUM", "BOOL", "TEXT");

    private final QcProductMapper productMapper;
    private final QcItemMapper itemMapper;
    private final QcBatchMapper batchMapper;
    private final ObjectMapper objectMapper;

    // ---------------- 产品 ----------------

    @GetMapping("/products")
    public R<List<QcProduct>> products(@RequestParam(required = false) String keyword) {
        return R.ok(productMapper.selectList(new LambdaQueryWrapper<QcProduct>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(QcProduct::getProductCode, keyword)
                        .or().like(QcProduct::getProductName, keyword))
                .orderByDesc(QcProduct::getId)));
    }

    /** 产品一览：带标准数与批次使用量，用来一眼看出「哪个产品还没配启用标准」 */
    @GetMapping("/products/overview")
    public R<List<Map<String, Object>>> productOverview(@RequestParam(required = false) String keyword) {
        String kw = trimToNull(keyword);
        return R.ok(productMapper.overview(kw));
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcProduct> saveProduct(@RequestBody QcProduct product) {
        String code = trimToNull(product.getProductCode());
        if (code == null) {
            throw BizException.of("产品编号不能为空");
        }
        if (trimToNull(product.getProductName()) == null) {
            throw BizException.of("产品名称不能为空，光有编号看不出这是什么东西");
        }
        product.setProductCode(code);
        long dup = productMapper.selectCount(new LambdaQueryWrapper<QcProduct>()
                .eq(QcProduct::getProductCode, code)
                .ne(product.getId() != null, QcProduct::getId, product.getId()));
        if (dup > 0) {
            throw BizException.of("产品编号 " + code + " 已被占用，换一个");
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
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcItem> saveItem(@RequestBody QcItem item) {
        String code = trimToNull(item.getItemCode());
        if (code == null) {
            throw BizException.of("检验项编号不能为空");
        }
        if (trimToNull(item.getItemName()) == null) {
            throw BizException.of("检验项名称不能为空");
        }
        if (item.getValueType() == null) {
            throw BizException.of("必须指定数据类型");
        }
        if (!VALUE_TYPES.contains(item.getValueType())) {
            throw BizException.of("数据类型只能是 NUMBER / ENUM / BOOL / TEXT");
        }
        item.setItemCode(code);

        long dup = itemMapper.selectCount(new LambdaQueryWrapper<QcItem>()
                .eq(QcItem::getItemCode, code)
                .ne(item.getId() != null, QcItem::getId, item.getId()));
        if (dup > 0) {
            throw BizException.of("检验项编号 " + code + " 已被占用，换一个");
        }

        // ENUM 的选项就是判定依据：选项坏掉，这个检验项做出的所有结论都不可信
        if ("ENUM".equals(item.getValueType())) {
            assertEnumOptions(item);
        }
        // BOOL 的合格值决定「是」算合格还是「否」算合格。留空的话判定层只能猜，
        // 猜错方向的结果是整批货的结论反过来——这是绝不能让它存进去的错。
        if ("BOOL".equals(item.getValueType()) && item.getBoolPassValue() == null) {
            throw BizException.of("布尔型必须指明合格值：是「有」算合格，还是「无」算合格");
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
                                    @RequestParam(required = false) Long productId,
                                    @RequestParam(required = false) String keyword) {
        return R.ok(batchMapper.selectList(new LambdaQueryWrapper<QcBatch>()
                .eq(status != null && !status.isBlank(), QcBatch::getStatus, status)
                .eq(productId != null, QcBatch::getProductId, productId)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(QcBatch::getBatchNo, keyword)
                        .or().like(QcBatch::getSupplier, keyword)
                        .or().like(QcBatch::getMaterialBatchNo, keyword)
                        .or().like(QcBatch::getWorkOrderNo, keyword))
                .orderByDesc(QcBatch::getId)));
    }

    /** 供应商来料质量排名。件级合格率最低的排在最前面——好供应商不用管，坏的要 */
    @GetMapping("/batches/stat/supplier")
    public R<List<Map<String, Object>>> supplierQuality() {
        return R.ok(batchMapper.supplierQuality());
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAnyRole('ADMIN','INSPECTOR')")
    public R<QcBatch> saveBatch(@RequestBody QcBatch batch) {
        String batchNo = trimToNull(batch.getBatchNo());
        if (batchNo == null) {
            throw BizException.of("批次号不能为空，后续检验单和出货记录都靠它串起来");
        }
        if (batch.getProductId() == null) {
            throw BizException.of("必须指定产品");
        }
        if (productMapper.selectById(batch.getProductId()) == null) {
            throw BizException.of("产品不存在，请先在主数据里建产品");
        }
        if (batch.getQty() == null || batch.getQty() <= 0) {
            throw BizException.of("批次数量必须大于 0");
        }
        String inspectType = trimToNull(batch.getInspectType());
        inspectType = inspectType == null ? "FQC" : inspectType;
        if (!INSPECT_TYPES.contains(inspectType)) {
            throw BizException.of("检验类型只能是 IQC / IPQC / FQC / OQC");
        }
        batch.setBatchNo(batchNo);
        batch.setInspectType(inspectType);

        long dup = batchMapper.selectCount(new LambdaQueryWrapper<QcBatch>()
                .eq(QcBatch::getBatchNo, batchNo)
                .ne(batch.getId() != null, QcBatch::getId, batch.getId()));
        if (dup > 0) {
            throw BizException.of("批次号 " + batchNo + " 已存在，同一批料不要重复登记");
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

    // ---------------- 内部 ----------------

    private void assertEnumOptions(QcItem item) {
        String raw = trimToNull(item.getOptionsJson());
        if (raw == null) {
            throw BizException.of("枚举型必须配置选项，否则录入时无值可选");
        }
        List<EnumOption> options;
        try {
            options = objectMapper.readValue(raw, new TypeReference<List<EnumOption>>() {
            });
        } catch (Exception e) {
            throw BizException.of("枚举选项格式不对，应为 [{\"code\":\"OK\",\"name\":\"无划伤\",\"pass\":true}]");
        }
        if (options == null || options.isEmpty()) {
            throw BizException.of("枚举型至少要有一个选项");
        }
        for (EnumOption o : options) {
            if (o == null || trimToNull(o.code()) == null || trimToNull(o.name()) == null) {
                throw BizException.of("每个选项都要有 code 和 name");
            }
        }
        if (options.stream().noneMatch(EnumOption::pass)) {
            throw BizException.of("至少要有一个选项判为合格，否则这个检验项永远不合格");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
