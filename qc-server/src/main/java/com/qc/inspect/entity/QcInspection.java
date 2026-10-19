package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_inspection")
public class QcInspection {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inspectionNo;

    private Long batchId;

    /** 使用的标准 ID */
    private Long standardId;

    /** 快照：建单时使用的标准版本 */
    private Integer standardVersion;

    private Long productId;
    private String inspectType;
    private String samplingType;

    /** 抽检件数 N */
    private Integer sampleQty;

    /** 快照：建单时的允收数 Ac（允许的不合格件数） */
    private Integer acceptQty;

    private Long inspectorId;
    private String inspectorName;

    /** DRAFT / SUBMITTED / CLOSED / VOID */
    private String status;

    /** PASS / FAIL / NA */
    private String result;

    /** 合格件数 */
    private Integer passedQty;

    /** 不合格件数 */
    private Integer failedQty;

    /** 不合格行数（件 × 项） */
    private Integer ngCount;
    private Integer criticalNgCount;

    /** 整批判定说明 */
    private String judgeReason;

    private LocalDateTime inspectedAt;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
