package com.qc.inspect.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 建检验单请求：只需给批次，检验项由生效标准自动快照生成。
 */
public record InspectionCreateReq(
        @NotNull(message = "批次不能为空") Long batchId,
        String inspectorName,
        Integer sampleQty
) {
}
