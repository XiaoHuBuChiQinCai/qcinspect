package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /** ACTIVE / SUSPENDED / SCRAPPED */
    private String status;

    /** 停用原因：校准不合格 / 点检不合格。停用时写入，复校合格后清空 */
    private String suspendReason;

    private String ownerDept;
    private String remark;

    /**
     * 最近一次点检日期。瞬态字段，由 {@code GaugeService.list} 按 id 批量回填。
     *
     * <p>不落库：落库就要在每次点检时同步，总有一次忘了同步，
     * 表现是「点检记录有、台账显示没点检」，比没有这一列更难查。
     */
    @TableField(exist = false)
    private LocalDate lastCheckDate;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
