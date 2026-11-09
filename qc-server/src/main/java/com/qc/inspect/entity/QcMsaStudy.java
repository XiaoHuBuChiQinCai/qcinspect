package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 量具测量系统分析（GR&amp;R 研究）的一次记录。
 *
 * <p>只存原始读数，不存 %GRR / ndc。那两样是读数的函数，落库就会变成
 * 「公式改了，旧结论还是旧的」——同一个数两个出处，早晚对不上。
 *
 * <p>{@code appraisers} 与 {@code readings} 是 jsonb，映射成 String 与
 * {@code qc_inspection_line.measured_values} 同一个路子：连接串开了
 * {@code stringtype=unspecified}，PG 自己按列类型转。
 */
@Data
@TableName("qc_msa_study")
public class QcMsaStudy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** MSA + yyyyMMdd + 3 位流水 */
    private String studyNo;

    private Long gaugeId;

    /** 快照：量具台账改名、报废都不改变历史结论的可追溯性 */
    private String gaugeCode;

    private String gaugeName;

    private Long productId;

    private Long itemId;

    /** 被研究的特性：外径 / 壁厚 / 硬度 */
    private String characteristic;

    private String unit;

    private BigDecimal lsl;

    private BigDecimal usl;

    private Integer appraiserCount;

    private Integer partCount;

    private Integer trialCount;

    /** JSON：["张三","李四","王五"] */
    private String appraisers;

    /** JSON：[评价人][零件][试次]，三个维度与上面三个计数同形 */
    private String readings;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
