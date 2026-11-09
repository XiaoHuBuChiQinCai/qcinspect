package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知。
 *
 * <p>只承载「需要对方动作」的事件，不做全量消息中心：
 * 审核退回要检验员改单、让步接收要留档，这两类才值得推；
 * 「单据已归档」这种纯告知不推，推了就是噪音，久了没人看角标。
 */
@Data
@TableName("qc_notice")
public class QcNotice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人展示名，与 qc_inspection.created_by 同口径 */
    private String receiver;

    /** REVIEW_REJECT / REVIEW_PASS / CONCESSION */
    private String noticeType;

    private String title;
    private String content;

    /** INSPECTION / DEFECT / GAUGE */
    private String bizType;
    private Long bizId;

    /** 单据号快照，列表直接显示，不必回查业务表 */
    private String bizNo;

    /** 0 未读 / 1 已读 */
    private Integer readFlag;
    private LocalDateTime readAt;

    private LocalDateTime createdAt;

    /**
     * 企业微信推送状态：NONE 未启用 / PENDING 待发 / SENT 已发 / FAILED 失败 / SKIPPED 接收人未绑定。
     *
     * <p>四个字段都留在通知行上，而不是另建推送表：管理员要回答的问题
     * 永远是「这条通知送到手机没有」，一对一的东西不该拆成两张表去对。
     */
    private String pushState;

    /** 实际发给了哪个企业微信 UserID。排障时第一个要看的就是它 */
    private String pushTarget;

    private LocalDateTime pushAt;

    /** 失败原因原文，企业微信的 errcode 与 errmsg 都留着 */
    private String pushError;
}
