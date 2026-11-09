package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 返修工单（返修票）。
 *
 * <p>不良判出来、处置选「返工」之后，实物去了车间。原模型在这里只有一段备注，
 * 回答不了三件最基本的事：这几件现在在哪、谁在修、什么时候回来多少。
 * 这张表就是那张夹在坏件上的纸。
 *
 * <p>状态只有四个，刻意不做审批流：
 * <pre>
 *   OPEN 待返修 ──start──→ DOING 返修中 ──finish──→ DONE 已完工
 *      └──────────────── cancel ────────────────→ CANCELLED 已取消
 * </pre>
 * 返修是车间的活，不是办公室的活。加两级审批只会让票卡在系统里、件卡在返修区。
 */
@Data
@TableName("qc_rework_order")
public class QcReworkOrder {

    /** 待返修（已开票、未开工） */
    public static final String STATUS_OPEN = "OPEN";

    /** 返修中（已在返修区动手） */
    public static final String STATUS_DOING = "DOING";

    /** 已完工（返好 + 返废 = 送修） */
    public static final String STATUS_DONE = "DONE";

    /** 已取消（换了条路走：直接报废、转外协、并入下一批） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** RW + yyyyMMdd + 3 位流水 */
    private String reworkNo;

    /** 来源不良记录，一条不良只允许一张有效票 */
    private Long defectId;

    /** 冗余：来源检验单，按单/按批查返修不必再绕不良表 */
    private Long inspectionId;

    private Long batchId;

    private Long productId;

    private Long itemId;

    private String defectCode;

    private String defectName;

    /** 送修数量 */
    private Integer qty;

    /** 返修要求：怎么修、修到什么程度。空着的话返修工只能凭经验猜 */
    private String requirement;

    /** 返修人 / 返修工位，自由文本——车间很多返修工没有系统账号 */
    private String assignee;

    /** 要求完成日，超期要在待办里点名 */
    private LocalDate dueDate;

    /** OPEN / DOING / DONE / CANCELLED */
    private String status;

    /** 返好件数，完工时必填 */
    private Integer reworkedQty;

    /** 返废件数，完工时必填；返好 + 返废必须等于送修数量 */
    private Integer scrappedQty;

    /** 修废的原因说明 */
    private String finishNote;

    /** 完工后生成的复检单；全部返废时为空（没有可检的东西） */
    private Long recheckInspectionId;

    private String assignedBy;

    private LocalDateTime assignedAt;

    private LocalDateTime startedAt;

    private String finishedBy;

    private LocalDateTime finishedAt;

    private String cancelReason;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;

    // ==================== 视图字段（不是表列） ====================

    /** 产品名 */
    @TableField(exist = false)
    private String productName;

    /** 批次号 */
    @TableField(exist = false)
    private String batchNo;

    /** 来源检验单号 */
    @TableField(exist = false)
    private String inspectionNo;

    /** 复检单号 */
    @TableField(exist = false)
    private String recheckInspectionNo;

    /** 复检结论 PASS / FAIL / NA */
    @TableField(exist = false)
    private String recheckResult;

    /** 是否已超期（服务端按今天算好，不让浏览器拿本地时间比） */
    @TableField(exist = false)
    private Boolean overdue;

    /** 超期天数 */
    @TableField(exist = false)
    private Integer overdueDays;
}
