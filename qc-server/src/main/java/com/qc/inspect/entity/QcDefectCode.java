package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 不良代码字典。帕累托图按此维度统计，不接受自由输入。 */
@Data
@TableName("qc_defect_code")
public class QcDefectCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;
    private String name;

    /** 尺寸 / 外观 / 性能 / 材质 / 装配 */
    private String category;

    /** CRITICAL / MAJOR / MINOR */
    private String severity;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
}
