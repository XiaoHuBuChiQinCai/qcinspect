package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 量具点检（期间核查）。
 *
 * <p>和 {@link QcGaugeCalibration} 是两件事：校准由外部机构按周期做，出证书；
 * 点检是车间拿标准件每月自己量一遍。两者都会让量具停用，但停用的原因不同，
 * 处置也不同——见 {@code qc_gauge.suspend_reason}。
 */
@Data
@TableName("qc_gauge_check")
public class QcGaugeCheck {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long gaugeId;

    private LocalDate checkDate;

    /** 标准件编号，点检的依据。没有标准件的点检等于没做 */
    private String standardNo;

    private BigDecimal standardValue;

    private BigDecimal measuredValue;

    /** 允差（±），等于允差算合格 */
    private BigDecimal tolerance;

    /** 偏差 = 实测 - 标称，落库快照 */
    private BigDecimal deviation;

    /** PASS / FAIL */
    private String result;

    private String checkedBy;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
}
