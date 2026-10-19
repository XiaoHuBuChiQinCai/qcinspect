package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_defect")
public class QcDefect {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inspectionId;
    private Long inspectionLineId;

    /** 件号，不良记录必须能定位到具体哪一件 */
    private Integer pieceNo;

    private Long itemId;

    private String defectCode;
    private String defectName;
    private Integer defectQty;

    /** REWORK / SCRAP / CONCESSION / RETURN */
    private String disposition;

    private String dispositionNote;
    private String createdBy;
    private LocalDateTime createdAt;
}
