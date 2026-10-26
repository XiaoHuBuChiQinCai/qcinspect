package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商主数据。
 *
 * <p>批次上的 {@code supplier} 是名称快照，这里的是唯一真身。统计一律按
 * {@code supplier_id} 归并——否则同一家厂被录成三种写法，供应商合格率排名会裂成三行，
 * 最差的那家被稀释掉，「最差 5 家」这个结论就不成立了。
 */
@Data
@TableName("qc_supplier")
public class QcSupplier {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商编码，厂内唯一，如 SUP0001 */
    private String supplierCode;

    /** 全称，厂内唯一 */
    private String supplierName;

    private String shortName;

    /** MATERIAL 原材料 / OUTSOURCE 外协加工 / PACKAGING 包装材料 / SERVICE 服务 */
    private String category;

    private String contact;
    private String phone;

    /** A/B/C 评级。空不等于 C——未评级和差评是两回事 */
    private String level;

    /** ACTIVE 在用 / DISABLED 停用 */
    private String status;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
