package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检验明细。item_* / min / max / options 等字段均为建单时的标准快照，
 * 标准后续改版不会影响已生成的单据。
 */
@Data
@TableName("qc_inspection_line")
public class QcInspectionLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inspectionId;
    private Long itemId;

    /** 件号，从 1 开始。抽检 N 件时每个检验项有 N 行 */
    private Integer pieceNo;

    // ---------- 快照字段 ----------
    private String itemCode;
    private String itemName;
    private String groupName;
    private String valueType;
    private String valueMode;
    private Integer pointCount;
    private String unit;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private String optionsJson;
    private Boolean boolPassValue;
    private Boolean isCritical;
    private Integer seq;

    /** 快照：该检验项建议的测量手段（卡尺 / 千分尺 / 硬度计），移动端据此推荐量具 */
    private String measureTool;

    // ---------- 实测数据 ----------
    /** JSON 数组字符串，如 ["12.03","12.01","12.02"] */
    private String measuredValues;

    private String measuredText;

    /** 实际使用的量具，快照 gaugeCode 后与台账解耦 */
    private Long gaugeId;
    private String gaugeCode;

    /** PASS / FAIL / PENDING */
    private String result;

    private String judgeReason;
    private String remark;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
