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

    /**
     * 布尔型合格值，覆盖主数据。
     *
     * <p>null = 沿用 {@code qc_item.bool_pass_value}；true = 录「有」算合格，false = 录「无」算合格。
     * 这个字段必须允许三态，才能在「毛刺（有=坏）」和「去毛刺（有=好）」之间区分。
     */
    private Boolean boolPassValue;

    private Boolean isDestructive;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
