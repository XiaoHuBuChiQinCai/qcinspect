package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /** PENDING / PROCESSING / DONE / WAIVED */
    private String dispositionStatus;

    private String dispositionBy;
    private LocalDateTime dispositionAt;

    /** 让步接收必须有理由和授权人，DB 有 CHECK 约束兜底 */
    private String concessionReason;
    private String concessionApprovedBy;
    private LocalDateTime concessionApprovedAt;

    /** 返工后的复检单，形成「原单 NG → 返工 → 复检」链 */
    private Long recheckInspectionId;

    private String createdBy;
    private LocalDateTime createdAt;

    /* ---------- 非表字段：处置列表展示用 ---------- */

    /** 复检单单号。只给前端看 id 没有意义——人在群里说的是单号 */
    @TableField(exist = false)
    private String recheckInspectionNo;

    /** 复检单状态与结论，用来在列表上直接看出「返工到底修好没有」 */
    @TableField(exist = false)
    private String recheckStatus;

    @TableField(exist = false)
    private String recheckResult;
}
