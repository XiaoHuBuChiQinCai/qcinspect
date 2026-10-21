package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抽样方案（GB/T 2828.1 一类计数抽样表的一行）。
 *
 * <p>一行 = 「在某个检验水平、某个 AQL 下，批量落在 [min, max] 时，
 * 该抽多少件（n）、允许几件不合格（Ac）、到几件判拒收（Re）」。
 *
 * <p>做成表而不是常量，是因为这行数据属于「客户的验收约定」而不是「程序逻辑」：
 * 不同主机厂给的 AQL 不一样，改一行数据就能生效，不用重新发版；
 * 而且历史单据有快照，方案表改了不会污染已经判完的单。
 */
@Data
@TableName("qc_sampling_plan")
public class QcSamplingPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 方案组，如 GB2828-1。ANSI/ASQ Z1.4 与国标等价，可另建一组 */
    private String planCode;

    /** 一般检验水平 I / II / III，特殊水平 S-1 ~ S-4 */
    private String inspectionLevel;

    /** 接收质量限，单位 %。2.500 即 AQL 2.5 */
    private BigDecimal aql;

    /** 批量下限（含） */
    private Integer lotSizeMin;

    /** 批量上限（含） */
    private Integer lotSizeMax;

    /** 样本量字码 A~R */
    private String codeLetter;

    /** 样本量 n */
    private Integer sampleSize;

    /** 接收数 Ac */
    private Integer acceptQty;

    /** 拒收数 Re，一次抽样恒为 Ac + 1 */
    private Integer rejectQty;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
