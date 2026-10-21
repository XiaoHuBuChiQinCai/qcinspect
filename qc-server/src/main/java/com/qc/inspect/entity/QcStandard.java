package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("qc_standard")
public class QcStandard {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String standardCode;
    private Long productId;
    private Integer version;

    /** DRAFT / ACTIVE / OBSOLETE */
    private String status;

    /** FULL / SAMPLE / NONE */
    private String samplingType;

    /** FIXED 固定样本量 / AQL 按批量查抽样方案表 */
    private String samplingPlan;

    /** AQL 接收质量限，仅 AQL 模式使用，NULL 表示默认 2.5 */
    private java.math.BigDecimal aql;

    /** 检验水平 I / II / III，仅 AQL 模式使用，NULL 表示默认 II */
    private String inspectionLevel;

    /** FIXED 模式下的抽检件数；AQL 模式下不参与计算 */
    private Integer sampleSize;

    /** 非关键项允许 NG 的项数（FIXED 模式下的允收数 Ac） */
    private Integer allowedNgCount;

    private LocalDate effectiveFrom;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
