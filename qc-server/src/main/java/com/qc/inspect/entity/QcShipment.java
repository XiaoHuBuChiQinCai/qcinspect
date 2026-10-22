package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 出货记录。追溯的出向：没有它，客诉来了只能查到检验单，查不到货去了哪。 */
@Data
@TableName("qc_shipment")
public class QcShipment {

    /** 正常 */
    public static final String STATUS_NORMAL = "NORMAL";

    /** 已作废（红冲），不计入批次累计出货量 */
    public static final String STATUS_VOID = "VOID";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shipmentNo;
    private Long batchId;
    private String customer;
    private Integer qty;

    private LocalDateTime shippedAt;
    private String remark;

    /** NORMAL / VOID */
    private String status;

    /** 作废理由，必填（见 ShipmentService.voidShipment） */
    private String voidReason;

    private String voidedBy;
    private LocalDateTime voidedAt;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;

    /** 视图字段：批次号，列表展示用，不是表列 */
    @TableField(exist = false)
    private String batchNo;

    /** 视图字段：产品名，列表展示用 */
    @TableField(exist = false)
    private String productName;
}
