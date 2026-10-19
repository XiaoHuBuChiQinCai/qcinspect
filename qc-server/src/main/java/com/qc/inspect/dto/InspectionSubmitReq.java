package com.qc.inspect.dto;

import java.util.List;

/**
 * 提交检验结果请求。
 */
public record InspectionSubmitReq(
        List<LineValue> lines
) {
    /**
     * @param lineId    明细 ID
     * @param values    实测值，单值项传 1 个；多点项按 pointCount 传
     * @param textValue 文本型录入内容
     * @param gaugeId   该项实际使用的量具，数值项建议必填；后台会校验是否在校准有效期内
     */
    public record LineValue(Long lineId, List<String> values, String textValue, Long gaugeId) {
    }
}
