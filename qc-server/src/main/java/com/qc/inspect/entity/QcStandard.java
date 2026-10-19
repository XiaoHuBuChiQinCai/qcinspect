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

    private Integer sampleSize;

    /** 非关键项允许 NG 的项数 */
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
