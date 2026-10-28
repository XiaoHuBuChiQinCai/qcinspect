package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按已作废标准版本下的判定单做了复核的记录。
 *
 * <p>V13 的 stale-usage 只回答「哪些作废版本上还挂着判定单、值不值得查」，
 * 答不了「查过没有」。体系审核问的恰恰是后一句：「你们发现之后做了什么」。
 *
 * <p>只增不改：同一次复核的不同轮次各记一行，最新一条代表当前状态。
 * 覆盖旧记录等于把「当时查过、当时判断没问题」这个事实抹掉。
 */
@Data
@TableName("qc_stale_review")
public class QcStaleReview {

    /** 抽查后维持原判 */
    public static final String KEPT = "KEPT";

    /** 复核发现问题，需要重新处置（返工 / 让步 / 追溯 / 客户通知） */
    public static final String ACTION = "ACTION";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被复核的作废标准版本 */
    private Long standardId;

    /** 产品快照：标准被删、产品改名后这条记录仍要能自己解释自己 */
    private Long productId;
    private Integer version;
    private String standardCode;
    private String productName;

    /** 复核当时该版本的风险档快照 */
    private String riskLevel;

    /** KEPT / ACTION */
    private String verdict;

    /** 复核当时这一版下挂着的判定单数快照 */
    private Integer inspectionCount;

    /** 必填：抽查了哪几张、依据什么、结论怎么来的 */
    private String remark;

    private String reviewer;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    @TableLogic
    private Integer delFlag;

    /**
     * 派生字段，不落库。
     *
     * <p>不存文案而存码：文案随时可能要改口（比如以后想区分「返工」和「让步」），
     * 存进库里就成了历史数据里改不掉的旧说法。
     */
    public String getVerdictText() {
        if (KEPT.equals(verdict)) return "维持原判";
        if (ACTION.equals(verdict)) return "需要处理";
        return verdict;
    }
}
