package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("qc_item")
public class QcItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String itemCode;
    private String itemName;

    /** NUMBER / ENUM / BOOL / TEXT */
    private String valueType;

    private String unit;
    private BigDecimal defaultMin;
    private BigDecimal defaultMax;

    /** 枚举型选项 JSON，如 [{"code":"OK","name":"无","pass":true}] */
    private String optionsJson;

    /** 布尔型：录入该值时判为合格 */
    private Boolean boolPassValue;

    private String measureTool;
    private String groupName;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
