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

    /**
     * 供应商主数据 ID。统计一律按它归并——名称是给人看的，ID 是给报表用的。
     *
     * <p>为什么还留着 {@link #supplier} 文本：批次是历史事实，建批那天的供应商叫
     * 什么名字就该一直叫什么。供应商后来改名或合并，历史批次不该跟着变。
     */
    private Long supplierId;

    /** 供应商名称快照。列表、报告、追溯都用这一份，不随主数据改名而变化 */
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
