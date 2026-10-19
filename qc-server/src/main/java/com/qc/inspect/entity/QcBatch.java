package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_batch")
public class QcBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private String workOrderNo;
    private Long productId;

    /** IQC / IPQC / FQC / OQC */
    private String inspectType;

    private Integer qty;
    private String supplier;
    private String materialBatchNo;

    /** PENDING / INSPECTING / DONE */
    private String status;

    private LocalDateTime arrivedAt;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
