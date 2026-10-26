package com.qc.inspect.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcSupplier;
import com.qc.inspect.mapper.QcSupplierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 供应商主数据。
 *
 * <p>在此之前供应商只是批次表上的一个 varchar，手打进来。同一家厂写成
 * 「宁波精铸一厂」「宁波精铸一厂 」「宁波精铸」会在质量统计里变成三家，
 * 供应商合格率排名裂成三行，最差的那家被稀释掉——「最差 5 家」这个结论直接作废。
 *
 * <p>权限：读取对所有登录用户开放（建批次要选供应商），增改与停用限管理员。
 * 停用不删档——历史批次还要能追溯到当时那家厂。
 */
@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private static final Set<String> CATEGORIES = Set.of("MATERIAL", "OUTSOURCE", "PACKAGING", "SERVICE");
    private static final Set<String> LEVELS = Set.of("A", "B", "C");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final QcSupplierMapper supplierMapper;

    /** 一览：带引用批次数与件级合格率，列表页直接能看出哪家该谈话 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        return R.ok(supplierMapper.overview(trimToNull(keyword), trimToNull(status)));
    }

    /**
     * 下拉选项：只给在用的。
     *
     * <p>停用的供应商不该出现在新建批次的下拉里——它已经不做生意了，
     * 再让人选上就等于把一条死数据写进新记录。
     */
    @GetMapping("/options")
    public R<List<QcSupplier>> options() {
        return R.ok(supplierMapper.selectList(new LambdaQueryWrapper<QcSupplier>()
                .eq(QcSupplier::getStatus, "ACTIVE")
                .orderByAsc(QcSupplier::getSupplierCode)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcSupplier> save(@RequestBody QcSupplier supplier) {
        String name = trimToNull(supplier.getSupplierName());
        if (name == null) {
            throw BizException.of("供应商名称不能为空");
        }
        supplier.setSupplierName(name);

        String category = trimToNull(supplier.getCategory());
        category = category == null ? "MATERIAL" : category.toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw BizException.of("供应商类别只能是 MATERIAL 原材料 / OUTSOURCE 外协 / PACKAGING 包材 / SERVICE 服务");
        }
        supplier.setCategory(category);

        String level = trimToNull(supplier.getLevel());
        if (level != null) {
            level = level.toUpperCase();
            if (!LEVELS.contains(level)) {
                throw BizException.of("供应商评级只能是 A / B / C，未评级请留空——空不等于 C");
            }
        }
        supplier.setLevel(level);

        String status = trimToNull(supplier.getStatus());
        status = status == null ? "ACTIVE" : status.toUpperCase();
        if (!STATUSES.contains(status)) {
            throw BizException.of("供应商状态只能是 ACTIVE / DISABLED");
        }
        supplier.setStatus(status);

        // 名称唯一：同名两家厂在质量统计里必然被合并，要区分就加「一分厂」这样的后缀
        long dupName = supplierMapper.selectCount(new LambdaQueryWrapper<QcSupplier>()
                .eq(QcSupplier::getSupplierName, name)
                .ne(supplier.getId() != null, QcSupplier::getId, supplier.getId()));
        if (dupName > 0) {
            throw BizException.of("供应商「" + name + "」已存在。同一家厂只建一条档，别在名称上区分批次");
        }

        String code = trimToNull(supplier.getSupplierCode());
        if (code == null) {
            code = String.format("SUP%04d", supplierMapper.nextCodeSeq());
        }
        long dupCode = supplierMapper.selectCount(new LambdaQueryWrapper<QcSupplier>()
                .eq(QcSupplier::getSupplierCode, code)
                .ne(supplier.getId() != null, QcSupplier::getId, supplier.getId()));
        if (dupCode > 0) {
            throw BizException.of("供应商编码 " + code + " 已被占用，换一个");
        }
        supplier.setSupplierCode(code);

        if (supplier.getId() == null) {
            supplier.setCreatedAt(LocalDateTime.now());
            supplierMapper.insert(supplier);
        } else {
            if (supplierMapper.selectById(supplier.getId()) == null) {
                throw BizException.of("供应商不存在：" + supplier.getId());
            }
            supplier.setUpdatedAt(LocalDateTime.now());
            supplierMapper.updateById(supplier);
        }
        return R.ok(supplier);
    }

    /**
     * 停用 / 启用。
     *
     * <p>停用只影响下拉可选性，不动历史批次：批次上存的是名称快照加 supplier_id，
     * 报表照样能按这家厂统计。这就是「不删档」的意义。
     */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public R<QcSupplier> toggleStatus(@PathVariable Long id,
                                      @RequestParam String status) {
        String s = trimToNull(status);
        s = s == null ? "" : s.toUpperCase();
        if (!STATUSES.contains(s)) {
            throw BizException.of("供应商状态只能是 ACTIVE / DISABLED");
        }
        QcSupplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw BizException.of("供应商不存在：" + id);
        }
        supplier.setStatus(s);
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(supplier);
        return R.ok(supplier);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
