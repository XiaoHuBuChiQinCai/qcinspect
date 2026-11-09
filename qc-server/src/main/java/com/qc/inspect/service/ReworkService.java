package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.entity.QcReworkOrder;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcProductMapper;
import com.qc.inspect.mapper.QcReworkOrderMapper;
import com.qc.inspect.rework.ReworkBalance;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 返修工单。
 *
 * <p>把「不良判出来」和「返工后复检」之间那段真空补上：坏件送去了哪、谁在修、
 * 说好什么时候回来、回来几件好的几件废的。
 *
 * <p>三个刻意的设计：
 * <ul>
 *   <li><b>开票即视为处置选了返工。</b> 不要求先点一次「开始返工」再开票——
 *       实物已经送去返修区了，系统里却还停在「待处理」，这种不一致只会让人
 *       忘了改状态。开票这个动作本身就说明返工已经开始</li>
 *   <li><b>完工必须报数，且返好 + 返废 = 送修。</b> 少报一件就是账不齐，
 *       多报一件就是记错了票。库层还有 CHECK 拦一道</li>
 *   <li><b>全部返废时不开复检单，直接把不良改判报废。</b> 没有可检的东西，
 *       开一张 0 件的复检单毫无意义；而原来「返工完成必须有 PASS 的复检单」
 *       这条校验会让这条不良永远关不掉——实际就是死锁</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReworkService {

    private static final Logger log = LoggerFactory.getLogger(ReworkService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final QcReworkOrderMapper reworkMapper;
    private final QcDefectMapper defectMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;
    private final DocNoService docNoService;
    private final InspectionService inspectionService;
    private final DefectService defectService;
    private final NoticeService noticeService;

    /** 处置方式的显示名，报错时说人话 */
    private static String label(String disposition) {
        return switch (disposition == null ? "" : disposition) {
            case "REWORK" -> "返工";
            case "SCRAP" -> "报废";
            case "CONCESSION" -> "让步接收";
            case "RETURN" -> "退货";
            default -> disposition;
        };
    }

    // ==================== 开票 ====================

    /**
     * 从一条不良开返修票。
     *
     * <p>数量默认取不良记录的件数，但允许改：判出来 5 件不良，实际只送了 3 件去修，
     * 或者顺手多送了 1 件同型号的，都是车间常态。票上记的必须是**真的送去修的数量**，
     * 否则回来报数时永远对不上。
     */
    @Transactional
    public QcReworkOrder create(Long defectId, Integer qty, String requirement,
                                String assignee, LocalDate dueDate, String remark) {
        QcDefect d = defectId == null ? null : defectMapper.selectById(defectId);
        if (d == null) {
            throw BizException.of("不良记录不存在，可能已被删除");
        }
        // 一条不良只允许一张有效票：两张票意味着返修区两拨人各修一半，
        // 报数时两边都说自己那半是全部，加起来比实际多
        QcReworkOrder exist = findActiveByDefect(defectId);
        if (exist != null) {
            throw BizException.of("这条不良已经开着返修票 " + exist.getReworkNo()
                    + "（" + statusLabel(exist.getStatus()) + "），先去那张票上干活，不要重复开票");
        }
        if ("DONE".equals(d.getDispositionStatus())) {
            throw BizException.of("这条不良已经处置完成，没有需要返修的东西了");
        }
        if ("WAIVED".equals(d.getDispositionStatus())) {
            throw BizException.of("这条不良走了让步接收，已经特采放行，不能再返修");
        }
        if (d.getDisposition() != null && !"REWORK".equals(d.getDisposition())) {
            throw BizException.of("这条不良的处置是「" + label(d.getDisposition())
                    + "」，不是返工。要送修请先把处置方式改成返工，否则报废件会又出现在返修台上");
        }

        QcInspection origin = inspectionMapper.selectById(d.getInspectionId());
        if (origin == null) {
            throw BizException.of("这条不良所属的检验单不存在，数据可能被清理过");
        }
        if (!"SUBMITTED".equals(origin.getStatus()) && !"CLOSED".equals(origin.getStatus())) {
            throw BizException.of("原单 " + origin.getInspectionNo() + " 还没提交（当前 "
                    + origin.getStatus() + "），结论随时会变，现在送修没有依据");
        }

        int send = qty != null ? qty
                : (d.getDefectQty() == null || d.getDefectQty() <= 0 ? 1 : d.getDefectQty());
        ReworkBalance.Check qc = ReworkBalance.checkQty(send);
        if (!qc.ok()) {
            throw BizException.of(qc.message());
        }
        if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
            throw BizException.of("要求完成日 " + dueDate + " 早于今天，这张票一开出来就是超期的");
        }

        String operator = UserContext.operator(null);
        QcReworkOrder o = new QcReworkOrder();
        o.setReworkNo(docNoService.next("RW", 3,
                () -> reworkMapper.countByDayPrefix(LocalDate.now().format(DAY))));
        o.setDefectId(defectId);
        o.setInspectionId(d.getInspectionId());
        o.setBatchId(origin.getBatchId());
        o.setProductId(origin.getProductId());
        o.setItemId(d.getItemId());
        o.setDefectCode(d.getDefectCode());
        o.setDefectName(d.getDefectName());
        o.setQty(send);
        o.setRequirement(blankToNull(requirement));
        o.setAssignee(blankToNull(assignee));
        o.setDueDate(dueDate != null ? dueDate : ReworkBalance.defaultDueDate(LocalDate.now()));
        o.setStatus(QcReworkOrder.STATUS_OPEN);
        o.setRemark(blankToNull(remark));
        o.setCreatedBy(operator);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedBy(operator);
        o.setUpdatedAt(LocalDateTime.now());
        if (o.getAssignee() != null) {
            o.setAssignedBy(operator);
            o.setAssignedAt(LocalDateTime.now());
        }

        try {
            reworkMapper.insert(o);
        } catch (DuplicateKeyException e) {
            // 两个人同时点「开返修单」，唯一索引只放行一个。这里翻成一句人话，
            // 而不是把 PG 的 duplicate key 原文丢到界面上
            throw BizException.of("这条不良刚被别人开了返修票，刷新一下就能看到");
        }

        // 开票 = 返工已开始。不良的处置状态跟着走，避免出现
        // 「实物在返修区、系统里还写着待处理」
        defectMapper.update(null, new LambdaUpdateWrapper<QcDefect>()
                .eq(QcDefect::getId, defectId)
                .set(QcDefect::getDisposition, "REWORK")
                .set(QcDefect::getDispositionStatus, "PROCESSING")
                .set(QcDefect::getDispositionBy, operator)
                .set(QcDefect::getDispositionAt, LocalDateTime.now()));

        log.info("开返修票 {}：不良 {} / {} 件 / 返修人 {}", o.getReworkNo(), defectId, send, o.getAssignee());
        return detail(o.getId());
    }

    // ==================== 指派 / 开工 / 完工 / 取消 ====================

    /** 改派或补填返修人。返修人换了不换票——票跟着件走，不跟着人走 */
    @Transactional
    public QcReworkOrder assign(Long id, String assignee, LocalDate dueDate) {
        QcReworkOrder o = require(id);
        requireOpen(o, "改派");
        if (assignee == null || assignee.isBlank()) {
            throw BizException.of("返修人不能为空。写工位也行（比如「三号返修台」），但必须能对上人");
        }
        String operator = UserContext.operator(null);
        reworkMapper.update(null, new LambdaUpdateWrapper<QcReworkOrder>()
                .eq(QcReworkOrder::getId, id)
                .set(QcReworkOrder::getAssignee, assignee.trim())
                .set(dueDate != null, QcReworkOrder::getDueDate, dueDate)
                .set(QcReworkOrder::getAssignedBy, operator)
                .set(QcReworkOrder::getAssignedAt, LocalDateTime.now())
                .set(QcReworkOrder::getUpdatedBy, operator)
                .set(QcReworkOrder::getUpdatedAt, LocalDateTime.now()));
        return detail(id);
    }

    /** 开工：只是让人知道返修工已经动手了，不改不良状态（开票时已经置为处理中） */
    @Transactional
    public QcReworkOrder start(Long id) {
        QcReworkOrder o = require(id);
        if (QcReworkOrder.STATUS_DOING.equals(o.getStatus())) {
            return detail(id);
        }
        requireOpen(o, "开工");
        String operator = UserContext.operator(null);
        reworkMapper.update(null, new LambdaUpdateWrapper<QcReworkOrder>()
                .eq(QcReworkOrder::getId, id)
                .set(QcReworkOrder::getStatus, QcReworkOrder.STATUS_DOING)
                .set(QcReworkOrder::getStartedAt, LocalDateTime.now())
                .set(QcReworkOrder::getUpdatedBy, operator)
                .set(QcReworkOrder::getUpdatedAt, LocalDateTime.now()));
        return detail(id);
    }

    /**
     * 完工报数。
     *
     * @return 返修票 + 自动生成的复检单信息（全部返废时为 null）
     */
    @Transactional
    public Map<String, Object> finish(Long id, Integer reworkedQty, Integer scrappedQty, String finishNote) {
        QcReworkOrder o = require(id);
        requireOpen(o, "完工");

        ReworkBalance.Check c = ReworkBalance.checkFinish(o.getQty(), reworkedQty, scrappedQty);
        if (!c.ok()) {
            throw BizException.of(c.message());
        }
        if (scrappedQty > 0 && (finishNote == null || finishNote.isBlank())) {
            throw BizException.of("有 " + scrappedQty + " 件在返修过程中报废了，请在说明里写清原因。"
                    + "返废件不会被任何报表统计到，除了这句话没有别的地方能留下它");
        }

        String operator = UserContext.operator(null);

        // 先把票置为已完工，再动不良。
        // 顺序不能反：全返废时要调 DefectService 把不良改判报废，而那里会拦
        // 「这条不良还挂着未完工的返修票」——先改不良就会自己挡住自己。
        // 整个方法在一个事务里，后面任何一步失败，这一步也一起回滚
        reworkMapper.update(null, new LambdaUpdateWrapper<QcReworkOrder>()
                .eq(QcReworkOrder::getId, id)
                .set(QcReworkOrder::getStatus, QcReworkOrder.STATUS_DONE)
                .set(QcReworkOrder::getReworkedQty, reworkedQty)
                .set(QcReworkOrder::getScrappedQty, scrappedQty)
                .set(QcReworkOrder::getFinishNote, blankToNull(finishNote))
                .set(QcReworkOrder::getFinishedBy, operator)
                .set(QcReworkOrder::getFinishedAt, LocalDateTime.now())
                .set(QcReworkOrder::getUpdatedBy, operator)
                .set(QcReworkOrder::getUpdatedAt, LocalDateTime.now()));

        Long recheckId = null;
        String recheckNo = null;

        if (reworkedQty > 0) {
            QcDefect d = defectMapper.selectById(o.getDefectId());
            Long linked = d == null ? null : d.getRecheckInspectionId();
            QcInspection existing = linked == null ? null : inspectionMapper.selectById(linked);
            if (existing != null && !"VOID".equals(existing.getStatus())) {
                // 检验员可能已经手工开过复检单。沿用比再开一张好：
                // 一张不良只该有一张有效复检单，多开的必然有一张没人填
                recheckId = existing.getId();
                recheckNo = existing.getInspectionNo();
            } else {
                Map<String, Object> r = inspectionService.createRecheck(o.getDefectId(), reworkedQty, operator);
                recheckId = (Long) r.get("inspectionId");
                recheckNo = (String) r.get("inspectionNo");
            }
            reworkMapper.update(null, new LambdaUpdateWrapper<QcReworkOrder>()
                    .eq(QcReworkOrder::getId, id)
                    .set(QcReworkOrder::getRecheckInspectionId, recheckId));
        } else {
            // 全部返废：这批件在返修台上死掉了。不良直接改判报废并关闭，
            // 否则「返工完成必须有 PASS 复检单」的校验会让它永远关不掉
            defectService.dispose(o.getDefectId(), "SCRAP", "DONE",
                    "返修票 " + o.getReworkNo() + " 完工时全部返废（" + scrappedQty + " 件）"
                            + (finishNote == null || finishNote.isBlank() ? "" : "：" + finishNote.trim()),
                    operator);
        }

        // 复检单生成了要通知检验员：下一步是他的活（去填实测量）
        if (recheckId != null && recheckNo != null) {
            QcInspection origin = inspectionMapper.selectById(o.getInspectionId());
            noticeService.push(origin == null ? null : origin.getInspectorName(),
                    NoticeService.TYPE_REWORK_DONE, "返修完成待复检",
                    "返修票 " + o.getReworkNo() + " 完工：返好 " + reworkedQty + " 件"
                            + (scrappedQty > 0 ? "、返废 " + scrappedQty + " 件" : "")
                            + "，复检单 " + recheckNo + " 已生成",
                    "INSPECTION", recheckId, recheckNo);
        }

        log.info("返修票 {} 完工：返好 {} 件 / 返废 {} 件 / 复检单 {}", o.getReworkNo(), reworkedQty, scrappedQty, recheckNo);
        QcReworkOrder fresh = detail(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", fresh);
        out.put("recheckInspectionId", recheckId);
        out.put("recheckInspectionNo", recheckNo);
        return out;
    }

    /** 取消：这批件换了条路走，理由必须写清，否则等于让它们从记录里消失 */
    @Transactional
    public QcReworkOrder cancel(Long id, String reason) {
        QcReworkOrder o = require(id);
        requireOpen(o, "取消");
        if (reason == null || reason.isBlank()) {
            throw BizException.of("取消返修票要写理由：改成报废了、转外协了、还是并入下一批？");
        }
        String operator = UserContext.operator(null);
        reworkMapper.update(null, new LambdaUpdateWrapper<QcReworkOrder>()
                .eq(QcReworkOrder::getId, id)
                .set(QcReworkOrder::getStatus, QcReworkOrder.STATUS_CANCELLED)
                .set(QcReworkOrder::getCancelReason, reason.trim())
                .set(QcReworkOrder::getUpdatedBy, operator)
                .set(QcReworkOrder::getUpdatedAt, LocalDateTime.now()));

        // 不良退回「待处理」，处置方式保留不动：票取消了，处置要不要改由人重新定
        defectMapper.update(null, new LambdaUpdateWrapper<QcDefect>()
                .eq(QcDefect::getId, o.getDefectId())
                .eq(QcDefect::getDispositionStatus, "PROCESSING")
                .set(QcDefect::getDispositionStatus, "PENDING")
                .set(QcDefect::getDispositionNote,
                        "返修票 " + o.getReworkNo() + " 已取消：" + reason.trim()));
        return detail(id);
    }

    // ==================== 查询 ====================

    public Page<QcReworkOrder> page(long current, long size, String status, String keyword,
                                   String from, String to, Boolean overdueOnly) {
        String kw = blankToNull(keyword);
        LocalDate today = LocalDate.now();

        LambdaQueryWrapper<QcReworkOrder> w = new LambdaQueryWrapper<QcReworkOrder>()
                .eq(!isBlank(status), QcReworkOrder::getStatus, status)
                .in(Boolean.TRUE.equals(overdueOnly), QcReworkOrder::getStatus,
                        QcReworkOrder.STATUS_OPEN, QcReworkOrder.STATUS_DOING)
                .lt(Boolean.TRUE.equals(overdueOnly), QcReworkOrder::getDueDate, today)
                .and(kw != null, q -> q
                        .like(QcReworkOrder::getReworkNo, kw)
                        .or()
                        .like(QcReworkOrder::getDefectName, kw)
                        .or()
                        .like(QcReworkOrder::getAssignee, kw)
                        // 批次号不是本表列，用 EXISTS；占位符必须 CAST 成 text，
                        // 否则 PostgreSQL 的 CONCAT 推断不出参数类型，接口 500
                        .or()
                        .apply("EXISTS (SELECT 1 FROM qc_batch b WHERE b.id = qc_rework_order.batch_id"
                                + " AND b.batch_no LIKE CONCAT('%', CAST({0} AS text), '%'))", kw))
                .apply(blankToNull(from) != null, "created_at >= CAST({0} AS date)", blankToNull(from))
                .apply(blankToNull(to) != null, "created_at < CAST({0} AS date) + interval '1 day'", blankToNull(to))
                .orderByDesc(QcReworkOrder::getCreatedAt)
                .orderByDesc(QcReworkOrder::getId);

        Page<QcReworkOrder> page = new Page<>(Math.max(current, 1), Math.min(Math.max(size, 1), 200));
        reworkMapper.selectPage(page, w);
        fill(page.getRecords());
        return page;
    }

    public QcReworkOrder detail(Long id) {
        QcReworkOrder o = require(id);
        List<QcReworkOrder> one = new ArrayList<>();
        one.add(o);
        fill(one);
        return one.get(0);
    }

    /**
     * 未完工的返修票，按「超期优先、要求完成日近的在前」排。
     *
     * <p>工作台和手机待办都用它。为什么先排超期：返修票没有审批环节，
     * 唯一会让人回头看它的理由就是「这件说好昨天回来，现在还没回来」。
     */
    public List<QcReworkOrder> pending(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        List<QcReworkOrder> rows = reworkMapper.selectList(new LambdaQueryWrapper<QcReworkOrder>()
                .in(QcReworkOrder::getStatus, QcReworkOrder.STATUS_OPEN, QcReworkOrder.STATUS_DOING)
                .orderByAsc(QcReworkOrder::getDueDate)
                .orderByAsc(QcReworkOrder::getId)
                .last("LIMIT " + n));
        fill(rows);
        // 超期的提到最前：due_date 空值在 PG 里排最后，正好符合「没期限的不催」
        rows.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.getOverdue()), Boolean.TRUE.equals(a.getOverdue())));
        return rows;
    }

    public long countOpen() {
        return reworkMapper.selectCount(new LambdaQueryWrapper<QcReworkOrder>()
                .in(QcReworkOrder::getStatus, QcReworkOrder.STATUS_OPEN, QcReworkOrder.STATUS_DOING));
    }

    public long countOverdue() {
        return reworkMapper.selectCount(new LambdaQueryWrapper<QcReworkOrder>()
                .in(QcReworkOrder::getStatus, QcReworkOrder.STATUS_OPEN, QcReworkOrder.STATUS_DOING)
                .lt(QcReworkOrder::getDueDate, LocalDate.now()));
    }

    /** 某张检验单下的返修票，检验单详情页用 */
    public List<QcReworkOrder> listByInspection(Long inspectionId) {
        if (inspectionId == null) {
            return List.of();
        }
        List<QcReworkOrder> rows = reworkMapper.selectList(new LambdaQueryWrapper<QcReworkOrder>()
                .eq(QcReworkOrder::getInspectionId, inspectionId)
                .orderByDesc(QcReworkOrder::getId));
        fill(rows);
        return rows;
    }

    // ==================== 内部 ====================

    private QcReworkOrder require(Long id) {
        QcReworkOrder o = id == null ? null : reworkMapper.selectById(id);
        if (o == null) {
            throw BizException.of("返修票不存在，可能已被删除");
        }
        return o;
    }

    /** 只有 OPEN / DOING 能操作。错误文案要说清当前状态，不要只丢一句「状态不允许」 */
    private void requireOpen(QcReworkOrder o, String action) {
        if (QcReworkOrder.STATUS_DONE.equals(o.getStatus())) {
            throw BizException.of("返修票 " + o.getReworkNo() + " 已完工（返好 "
                    + o.getReworkedQty() + " 件、返废 " + o.getScrappedQty() + " 件），不能再" + action);
        }
        if (QcReworkOrder.STATUS_CANCELLED.equals(o.getStatus())) {
            throw BizException.of("返修票 " + o.getReworkNo() + " 已取消（" + o.getCancelReason() + "），不能再" + action);
        }
    }

    private QcReworkOrder findActiveByDefect(Long defectId) {
        return reworkMapper.selectOne(new LambdaQueryWrapper<QcReworkOrder>()
                .eq(QcReworkOrder::getDefectId, defectId)
                .ne(QcReworkOrder::getStatus, QcReworkOrder.STATUS_CANCELLED)
                .last("LIMIT 1"));
    }

    private void fill(List<QcReworkOrder> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<Long> productIds = rows.stream().map(QcReworkOrder::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> batchIds = rows.stream().map(QcReworkOrder::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> inspectionIds = rows.stream().map(QcReworkOrder::getInspectionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> recheckIds = rows.stream().map(QcReworkOrder::getRecheckInspectionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        inspectionIds.addAll(recheckIds);

        Map<Long, String> productNames = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(QcProduct::getId, QcProduct::getProductName, (a, b) -> a));
        Map<Long, String> batchNos = batchIds.isEmpty() ? Map.of()
                : batchMapper.selectBatchIds(batchIds).stream()
                .collect(Collectors.toMap(QcBatch::getId, QcBatch::getBatchNo, (a, b) -> a));
        Map<Long, QcInspection> insMap = inspectionIds.isEmpty() ? Map.of()
                : inspectionMapper.selectBatchIds(inspectionIds).stream()
                .collect(Collectors.toMap(QcInspection::getId, i -> i, (a, b) -> a));

        LocalDate today = LocalDate.now();
        for (QcReworkOrder o : rows) {
            o.setProductName(productNames.get(o.getProductId()));
            o.setBatchNo(batchNos.get(o.getBatchId()));
            QcInspection origin = insMap.get(o.getInspectionId());
            o.setInspectionNo(origin == null ? null : origin.getInspectionNo());
            QcInspection recheck = o.getRecheckInspectionId() == null ? null : insMap.get(o.getRecheckInspectionId());
            if (recheck != null) {
                o.setRecheckInspectionNo(recheck.getInspectionNo());
                o.setRecheckResult(recheck.getResult());
            }
            // 超期由服务端算好下发：手机时间可能不准，拿本地时间比会把「今天到期」判成超期
            o.setOverdue(ReworkBalance.overdue(o.getDueDate(), o.getStatus(), today));
            o.setOverdueDays(ReworkBalance.overdueDays(o.getDueDate(), today));
        }
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "OPEN" -> "待返修";
            case "DOING" -> "返修中";
            case "DONE" -> "已完工";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
