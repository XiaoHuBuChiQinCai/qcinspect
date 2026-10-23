package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 量具校准履历：一次校准一行，只增不改。
 *
 * <p>为什么不复用 {@code qc_gauge.calibrate_date}：那是「当前有效期」的快照，
 * 覆盖式更新会把历史证据擦掉。体系审核要的是完整链条——哪天、谁校的、证书几号、结论如何。
 *
 * <p>{@code nextCalibrateDate} 存的是**当时**按当时周期算出的到期日，
 * 不跟着后来的周期调整而变，否则回看历史会觉得当年算错了。
 */
@Data
@TableName("qc_gauge_calibration")
public class QcGaugeCalibration {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long gaugeId;

    private LocalDate calibrateDate;

    /** 当次使用的校准周期（天） */
    private Integer cycleDays;

    private LocalDate nextCalibrateDate;

    /** PASS 合格 / FAIL 不合格 / LIMITED 限用 */
    private String result;

    /** 校准证书编号，外审必查 */
    private String certificateNo;

    /** 校准机构：法定计量院、第三方实验室，或「内部校准」 */
    private String agency;

    /** 登记人（不等于送检人，留痕用） */
    private String calibrateBy;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
}
