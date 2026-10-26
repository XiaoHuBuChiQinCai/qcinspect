package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_batch")
public class QcBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private String workOrderNo;
    private Long productId;

    /** IQC / IPQC / FQC / OQC */
    private String inspectType;

    private Integer qty;

    /**
     * 供应商主数据 ID。统计一律按它归并——名称是给人看的，ID 是给报表用的。
     *
     * <p>为什么还留着 {@link #supplier} 文本：批次是历史事实，建批那天的供应商叫
     * 什么名字就该一直叫什么。供应商后来改名或合并，历史批次不该跟着变。
     */
    private Long supplierId;

    /** 供应商名称快照。列表、报告、追溯都用这一份，不随主数据改名而变化 */
    private String supplier;

    private String materialBatchNo;

    /** PENDING / INSPECTING / DONE */
    private String status;

    private LocalDateTime arrivedAt;
    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;

    // ---------------- 检验进度（瞬态，不落库） ----------------
    //
    // 车间问得最多的一句话是「这批检到哪了」，而批次表自己不知道——
    // 它只有 qty（这批有多少件），检了几件在 qc_inspection 里。
    //
    // 做成瞬态字段而不是视图或新表：进度随时在变，落库就要有人负责同步，
    // 一旦哪条路径忘了同步，页面上的数字就永远是错的，而且看不出它错。
    // 现算的代价是一条按 id 的聚合查询，换来「不可能不同步」。

    /** 有效检验单数（不含作废单）。返工复检会算成第二单，这是对的 */
    @TableField(exist = false)
    private Integer lotCount;

    /** 抽检件数之和。草稿单也计入：有人已经在检了，这不算「没开始」 */
    @TableField(exist = false)
    private Integer sampledQty;

    /**
     * 已判定件数之和 = 合格 + 不合格。
     *
     * <p>草稿单贡献 0：件数是提交判定后才回写的。所以
     * {@code sampledQty - doneQty} 恰好是「已抽出来但还没判」的件数。
     */
    @TableField(exist = false)
    private Integer doneQty;

    @TableField(exist = false)
    private Integer passedQty;

    @TableField(exist = false)
    private Integer failedQty;
}
