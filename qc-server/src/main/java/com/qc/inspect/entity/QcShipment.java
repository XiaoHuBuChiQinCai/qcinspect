package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 出货记录。追溯的出向：没有它，客诉来了只能查到检验单，查不到货去了哪。 */
@Data
@TableName("qc_shipment")
public class QcShipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shipmentNo;
    private Long batchId;
    private String customer;
    private Integer qty;

    private LocalDateTime shippedAt;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
}
