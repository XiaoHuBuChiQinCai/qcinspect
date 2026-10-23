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
 * 客户投诉。
 *
 * <p>追溯链的回向。原来 {@code qc_shipment} 是终点——货发出去，系统的事就结束了。
 * 客户把不良品退回来的时候，这套系统无话可说。
 */
@Data
@TableName("qc_complaint")
public class QcComplaint {

    /** 待处理 */
    public static final String STATUS_OPEN = "OPEN";

    /** 处理中 */
    public static final String STATUS_PROCESSING = "PROCESSING";

    /** 已关闭（必须写根因、措施、责任方） */
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String complaintNo;

    /** 被投诉批次，必填 */
    private Long batchId;

    /** 对应出货单，可空 */
    private Long shipmentId;

    private String customer;

    private LocalDate complaintDate;

    /** APP / SIZE / PERF / PACK / MARK / OTHER */
    private String category;

    /** HIGH / MEDIUM / LOW */
    private String severity;

    /** 客户反馈的涉及数量，客户说不清时留空 */
    private Integer qty;

    private String description;

    /** OPEN / PROCESSING / CLOSED */
    private String status;

    private String rootCause;
    private String action;
    private String responsibility;

    private String closedBy;
    private LocalDateTime closedAt;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;

    /** 视图字段：批次号，列表与追溯展示用，不是表列 */
    @TableField(exist = false)
    private String batchNo;

    /** 视图字段：产品名 */
    @TableField(exist = false)
    private String productName;

    /** 视图字段：出货单号 */
    @TableField(exist = false)
    private String shipmentNo;
}
