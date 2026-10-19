package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("qc_standard_item")
public class QcStandardItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long standardId;
    private Long itemId;
    private Integer seq;
    private String groupName;
    private Boolean isCritical;
    private Boolean isRequired;

    /** SINGLE / MULTIPLE */
    private String valueMode;

    private Integer pointCount;
    private BigDecimal minValue;
    private BigDecimal maxValue;

    /** 覆盖检验项主数据的选项，为空则用主数据 */
    private String optionsJson;

    private Boolean isDestructive;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
