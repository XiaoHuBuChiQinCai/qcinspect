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
 * 纠正预防措施（CAPA）。
 *
 * <p>系统能算的东西已经不少：帕累托说哪类不良最多，SPC 说过程稳不稳，GR&amp;R 说量出来的数信不信，
 * 返修票说那批坏件现在在哪。四件事都答得清清楚楚，唯独答不上「同一个毛病为什么修了三次还在犯」。
 *
 * <p>帕累托上排第一的那根柱子，车间里是五件互不相干的麻烦事：每次都是「这一批返工掉」，
 * 没有人回头看它为什么反复出现。闭环只闭到「把坏件处理掉」，没闭到「让它不再出现」。
 * 这张表补的就是那半截。
 *
 * <p>状态只有五个，刻意不做审批流——措施有没有用不是批出来的，是验出来的。
 * <pre>
 *   OPEN 待分析 ──→ DOING 措施执行中 ──→ VERIFYING 待验证 ──(有效)──→ CLOSED
 *                          ↑                    │
 *                          └─────(无效)─────────┘
 *   任意未关闭态 ──cancel(理由)──→ CANCELLED
 * </pre>
 */
@Data
@TableName("qc_capa")
public class QcCapa {

    /** 待分析：问题记下来了，还没查原因 */
    public static final String STATUS_OPEN = "OPEN";

    /** 措施执行中：根因和措施在改 */
    public static final String STATUS_DOING = "DOING";

    /** 待验证：措施做完了，等别人来判断有没有用 */
    public static final String STATUS_VERIFYING = "VERIFYING";

    /** 已关闭：验证有效 */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 已撤销：问题搞错了，或并入了别的措施 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String SOURCE_DEFECT = "DEFECT";
    public static final String SOURCE_COMPLAINT = "COMPLAINT";
    public static final String SOURCE_AUDIT = "AUDIT";
    public static final String SOURCE_OTHER = "OTHER";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** CAPA + yyyyMMdd + 3 位流水 */
    private String capaNo;

    /** DEFECT / COMPLAINT / AUDIT / OTHER */
    private String sourceType;

    /** 来源记录 id，一个来源只许一张有效措施 */
    private Long sourceId;

    /** 来源单号快照：外审要能顺着措施倒回触发它的那张单 */
    private String sourceRef;

    /** 一句话说清是什么毛病，以后靠它认人 */
    private String title;

    /** 问题描述：什么产品、什么不良、影响多少件 */
    private String problem;

    /** 冗余产品，再犯检测按它查 */
    private Long productId;

    /**
     * 不良标识，再犯检测按它查。
     *
     * <p>优先存不良代码；<b>来源不良没有代码时存名称</b>。现场不少临时性缺陷没来得及建代码，
     * 按 code 匹配会把它们全漏掉——而漏掉的恰好是最容易反复的那一类。
     * 查询侧对应 {@code coalesce(defect_code, defect_name)}，两边必须同口径。
     */
    private String defectCode;

    /** CRITICAL / MAJOR / MINOR */
    private String severity;

    /** 根本原因。空着进不了验证环节 */
    private String rootCause;

    /** 纠正措施：针对这个根因做什么 */
    private String actionPlan;

    /** 预防措施：同类问题在别处怎么不发生。空着不拦关闭，但会提醒 */
    private String preventPlan;

    private String owner;

    private LocalDate dueDate;

    /** 开始处理的时间。空着说明这张措施建了还没人动过 */
    private LocalDateTime startedAt;

    /** OPEN / DOING / VERIFYING / CLOSED / CANCELLED */
    private String status;

    /** 验证是否有效。NULL = 未验证；FALSE = 措施不够，已退回 */
    private Boolean closeEffective;

    /** 验证结论：看了什么数据、连续几批没再出现 */
    private String verifyNote;

    private String verifiedBy;

    private LocalDateTime verifiedAt;

    private String closedBy;

    private LocalDateTime closedAt;

    private String cancelReason;

    private String remark;

    @TableLogic
    private Integer delFlag;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;

    // ==================== 视图字段（不是表列） ====================

    @TableField(exist = false)
    private String productName;

    /** 状态中文。服务端下发，前端不再自己维护一份文案 */
    @TableField(exist = false)
    private String statusText;

    /** 是否已超期（服务端按今天算好，不让浏览器拿本地时间比） */
    @TableField(exist = false)
    private Boolean overdue;

    @TableField(exist = false)
    private Integer overdueDays;

    /** 开了多少天（从建单到今天），用来判断这张措施是不是在躺着 */
    @TableField(exist = false)
    private Integer daysOpen;

    /**
     * 再犯提示。
     *
     * <p>同一个「产品 + 不良代码」上一次关过且验证有效、现在又出现时才有值——
     * 那说明上次的措施没起效，这是 CAPA 系统最该暴露的一件事。
     */
    @TableField(exist = false)
    private String recurrenceNote;
}
