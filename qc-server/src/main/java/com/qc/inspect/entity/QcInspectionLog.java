package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检验单操作日志。提交即锁定，任何状态变化都要留一条。
 */
@Data
@TableName("qc_inspection_log")
public class QcInspectionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inspectionId;

    /** CREATE / SUBMIT / REOPEN / CLOSE / VOID */
    private String action;

    private String fromStatus;
    private String toStatus;

    /** 解锁、作废必须填理由 */
    private String reason;

    private String operator;

    /** 动作结果摘要，比如判定结论 */
    private String summary;

    private LocalDateTime createdAt;
}
