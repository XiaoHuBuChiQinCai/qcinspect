package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_product")
public class QcProduct {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String productCode;
    private String productName;
    private String spec;
    private String unit;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
