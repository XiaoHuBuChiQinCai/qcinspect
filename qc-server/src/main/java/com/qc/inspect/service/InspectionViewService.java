package com.qc.inspect.service;

import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 列表展示字段补齐。
 *
 * <p>批次号、产品名不是 qc_inspection 的列，但每个列表页都要显示。
 * 与其让前端为每行再发一次请求，不如在服务端一次批量查出来——
 * 二十行的列表原本会变成二十次 HTTP，这在车间平板上很致命。
 */
@Service
@RequiredArgsConstructor
public class InspectionViewService {

    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;

    /** 就地补齐 batchNo / productName，入参为 null 或空集合时直接返回 */
    public void fill(List<QcInspection> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> batchIds = rows.stream().map(QcInspection::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> productIds = rows.stream().map(QcInspection::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, QcBatch> batches = batchIds.isEmpty() ? Map.of()
                : batchMapper.selectBatchIds(batchIds).stream()
                .collect(Collectors.toMap(QcBatch::getId, Function.identity(), (a, b) -> a));
        Map<Long, QcProduct> products = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(QcProduct::getId, Function.identity(), (a, b) -> a));

        for (QcInspection row : rows) {
            QcBatch batch = batches.get(row.getBatchId());
            QcProduct product = products.get(row.getProductId());
            row.setBatchNo(batch == null ? null : batch.getBatchNo());
            row.setProductName(product == null ? null : product.getProductName());
        }
    }
}
