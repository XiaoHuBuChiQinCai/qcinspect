package com.qc.inspect.msa;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次 GR&amp;R 研究的对外视图。
 *
 * <p>为什么不直接把实体扔给前端：实体里的 {@code readings} 是一串 jsonb 文本，
 * 直接序列化出去会变成一坨转义过的字符串，前端还得自己再 parse 一次。这里顺手
 * 解析成三维数组，并把 {@link GrrStudy.Result} 一起带上——算在服务端做，
 * PC 和以后的移动端不需要各写一份。
 *
 * <p>列表接口不带 {@code readings}（null）：一页 10 条、每条 90 个读数，
 * 列表页一个也用不上。
 */
public record MsaView(
        Long id,
        String studyNo,
        Long gaugeId,
        String gaugeCode,
        String gaugeName,
        Long productId,
        String productName,
        Long itemId,
        String characteristic,
        String unit,
        BigDecimal lsl,
        BigDecimal usl,
        int appraiserCount,
        int partCount,
        int trialCount,
        List<String> appraisers,
        List<List<List<Double>>> readings,
        GrrStudy.Result result,
        String remark,
        String createdBy,
        LocalDateTime createdAt) {
}
