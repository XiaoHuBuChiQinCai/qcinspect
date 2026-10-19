package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 量具台账。
 *
 * <p>过校准期的量具测出的数据在质量体系上不被承认，所以录入时要拦，
 * 提交时还要再拦一次——录入到提交之间可能跨了一夜，刚好过期。
 */
@Data
@TableName("qc_gauge")
public class QcGauge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String gaugeCode;
    private String gaugeName;

    /** 卡尺 / 千分尺 / 硬度计 / CMM / 塞规 */
    private String gaugeType;

    private String accuracy;

    private LocalDate calibrateDate;
    private Integer calibrateCycleDays;

    /** 由 calibrateDate + cycle 推算，程序维护 */
    private LocalDate nextCalibrateDate;

    /** ACTIVE / SCRAPPED */
    private String status;

    private String ownerDept;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
