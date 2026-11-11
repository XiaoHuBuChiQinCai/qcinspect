package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.capa.CapaRules;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcCapa;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.mapper.QcCapaMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcProductMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纠正预防措施。
 *
 * <p>这个类里最要紧的不是增删改查，是两处判断：
 * <ol>
 *   <li>{@link #verify} 里「责任人不能验证自己」——CAPA 系统能不能起作用就取决于这一条。
 *       谁都愿意说自己的措施有效，一旦允许自验，表上全是「已验证有效」，问题照旧</li>
 *   <li>{@link #recurrence} 里的再犯检测——上一次关过、判过有效，现在又出现了，
 *       说明当时打偏了。这是最该被看见、也最容易被藏起来的一件事</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CapaService {

    private static final Logger log = LoggerFactory.getLogger(CapaService.class);

    private static final ZoneOffset BIZ_OFFSET = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR");
    private static final Set<String> SOURCES = Set.of("DEFECT", "COMPLAINT", "AUDIT", "OTHER");

    /**
     * 再犯回看窗口。90 天 ≈ 一个季度：太短看不见「改完慢慢回来」，太长会把
     * 早就不存在的问题算进「反复出现」，反而稀释掉真正在犯的那一条。
     */
    public static final int HISTORY_DAYS = 90;

    private final QcCapaMapper capaMapper;
    private final QcDefectMapper defectMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcProductMapper productMapper;
    private final DocNoService docNoService;
    private final NoticeService noticeService;

    // ------------------------------------------------------------------ 建单

    /**
     * 建单。
     *
     * <p>来源是 {@code DEFECT} 时，产品与不良标识**以来源不良为准**，不信前端传的：
     * 手工填的那两个值一旦和来源对不上，再犯检测就永远匹配不到自己，
     * 于是「同一个毛病反复犯」这件事在系统里彻底看不见。
     */
    @Transactional
    public QcCapa create(QcCapa req) {
        String operator = UserContext.username();

        String title = blankToNull(req.getTitle());
        String problem = blankToNull(req.getProblem());
        String owner = blankToNull(req.getOwner());
        CapaRules.Check c = CapaRules.checkCreate(title, problem, owner);
        if (!c.ok()) {
            throw BizException.of(c.message());
        }
        req.setTitle(title);
        req.setProblem(problem);
        req.setOwner(owner);

        String severity = blankToNull(req.getSeverity());
        severity = severity == null ? "MINOR" : severity.toUpperCase();
        if (!SEVERITIES.contains(severity)) {
            throw BizException.of("严重程度只能是 CRITICAL / MAJOR / MINOR");
        }
        req.setSeverity(severity);

        String sourceType = blankToNull(req.getSourceType());
        sourceType = sourceType == null ? QcCapa.SOURCE_OTHER : sourceType.toUpperCase();
        if (!SOURCES.contains(sourceType)) {
            throw BizException.of("来源只能是 DEFECT 不良 / COMPLAINT 客诉 / AUDIT 审核 / OTHER 其他");
        }
        req.setSourceType(sourceType);

        if (QcCapa.SOURCE_DEFECT.equals(sourceType) && req.getSourceId() != null) {
            QcDefect d = defectMapper.selectById(req.getSourceId());
            if (d == null) {
                throw BizException.of("来源不良不存在：" + req.getSourceId());
            }
            QcInspection insp = inspectionMapper.selectById(d.getInspectionId());
            if (insp != null) {
                req.setProductId(insp.getProductId());
            }
            // 没建代码的不良用名称当标识，和服务端查询侧 coalesce 同口径
            req.setDefectCode(blankToNull(d.getDefectCode()) != null
                    ? d.getDefectCode() : d.getDefectName());
            if (req.getSourceRef() == null || req.getSourceRef().isBlank()) {
                req.setSourceRef(insp == null ? null : insp.getInspectionNo());
            }
        }
        req.setDefectCode(blankToNull(req.getDefectCode()));
        req.setRootCause(blankToNull(req.getRootCause()));
        req.setActionPlan(blankToNull(req.getActionPlan()));
        req.setPreventPlan(blankToNull(req.getPreventPlan()));
        req.setRemark(blankToNull(req.getRemark()));

        // 已经写了根因和措施，就不再要求他再去点一次「开始处理」——那只是形式
        boolean started = req.getRootCause() != null && req.getActionPlan() != null;
        req.setStatus(started ? QcCapa.STATUS_DOING : QcCapa.STATUS_OPEN);
        req.setCreatedBy(operator);
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedBy(operator);
        req.setUpdatedAt(LocalDateTime.now());
        req.setDelFlag(0);
        req.setCloseEffective(null);
        req.setVerifiedBy(null);
        req.setVerifiedAt(null);
        req.setClosedBy(null);
        req.setClosedAt(null);

        String day = LocalDate.now().format(DAY);
        req.setCapaNo(docNoService.next("CAPA", 3, () -> capaMapper.countByDayPrefix(day)));

        try {
            capaMapper.insert(req);
        } catch (DuplicateKeyException e) {
            // 唯一索引是 (source_type, source_id) 的部分索引，并发下两个人都点了「建措施」
            throw BizException.of("这条来源刚被别人建过措施了，刷新一下就能看到。"
                    + "同一件事开两张措施，两边会各改各的，最后谁也说不清做完了没有");
        }

        notifyOwner(req, "有一条纠正措施等你处理",
                "「" + req.getTitle() + "」要求 " + (req.getDueDate() == null ? "尽快" : req.getDueDate() + " 前")
                        + " 完成。措施做完后要提交验证，验证有效才算关闭。");
        // 返回值也要带上状态文案与超期标记：前端建完立刻渲染这一行，
        // 少了这几个字段会显示成空白标签，看着像没建成功
        fill(List.of(req));
        return req;
    }

    // ------------------------------------------------------------------ 流转

    @Transactional
    public QcCapa start(Long id) {
        QcCapa capa = require(id);
        CapaRules.Check c = CapaRules.checkStart(capa.getStatus());
        if (!c.ok()) {
            throw BizException.of(c.message());
        }
        capa.setStatus(QcCapa.STATUS_DOING);
        capa.setStartedAt(LocalDateTime.now());
        touch(capa);
        return capa;
    }

    /**
     * 保存根因与措施。只有待分析和执行中可以改。
     *
     * <p>进了「待验证」就冻结：验证人看到的东西必须和提交时一致，边验边改等于追着一个移动的靶子。
     * 真要补，让验证人判无效退回来。
     */
    @Transactional
    public QcCapa saveAnalysis(Long id, String rootCause, String actionPlan, String preventPlan) {
        QcCapa capa = require(id);
        if (!QcCapa.STATUS_OPEN.equals(capa.getStatus()) && !QcCapa.STATUS_DOING.equals(capa.getStatus())) {
            throw BizException.of("「" + CapaRules.statusText(capa.getStatus()) + "」的措施不能改内容。"
                    + "要补充措施，先让验证人判「无效」退回来——否则验证人刚看过的措施会被悄悄改掉");
        }
        capa.setRootCause(blankToNull(rootCause));
        capa.setActionPlan(blankToNull(actionPlan));
        capa.setPreventPlan(blankToNull(preventPlan));
        if (QcCapa.STATUS_OPEN.equals(capa.getStatus())
                && capa.getRootCause() != null && capa.getActionPlan() != null) {
            capa.setStatus(QcCapa.STATUS_DOING);
            capa.setStartedAt(LocalDateTime.now());
        }
        touch(capa);
        return capa;
    }

    @Transactional
    public QcCapa submitVerify(Long id) {
        QcCapa capa = require(id);
        CapaRules.Check c = CapaRules.checkSubmitVerify(
                capa.getStatus(), capa.getRootCause(), capa.getActionPlan());
        if (!c.ok()) {
            throw BizException.of(c.message());
        }
        capa.setStatus(QcCapa.STATUS_VERIFYING);
        touch(capa);
        return capa;
    }

    /**
     * 验证。有效则关闭，无效则退回「措施执行中」。
     *
     * <p>两条硬规矩写在方法里而不是文档里：
     * <ul>
     *   <li><b>责任人不能验自己</b>。谁都觉得自己的措施有用；一旦可以自验，
     *       表上全是「已验证有效」，而问题照旧在产线上发生</li>
     *   <li>判「无效」不是失败，是正常路径。措施不够就退回去改，
     *       这比硬着头皮关掉、下个月再犯一遍要便宜得多</li>
     * </ul>
     */
    @Transactional
    public QcCapa verify(Long id, Boolean effective, String note) {
        QcCapa capa = require(id);
        CapaRules.Check c = CapaRules.checkVerify(capa.getStatus(), effective, note);
        if (!c.ok()) {
            throw BizException.of(c.message());
        }
        String operator = UserContext.username();
        if (operator.equals(capa.getOwner())) {
            throw BizException.of("这条措施的责任人就是你。自己验自己等于没验——"
                    + "找个没参与这条措施的人，让他看数据点这一下，哪怕只是拉着一起看一遍");
        }

        capa.setCloseEffective(effective);
        capa.setVerifyNote(note.trim());
        capa.setVerifiedBy(operator);
        capa.setVerifiedAt(LocalDateTime.now());

        if (Boolean.TRUE.equals(effective)) {
            capa.setStatus(QcCapa.STATUS_CLOSED);
            capa.setClosedBy(operator);
            capa.setClosedAt(LocalDateTime.now());
            String note2 = CapaRules.closingNote(capa.getPreventPlan());
            if (note2 != null) {
                capa.setRemark(appendRemark(capa.getRemark(), note2));
            }
            notifyOwner(capa, "你的一条纠正措施已关闭",
                    "「" + capa.getTitle() + "」验证有效，已关闭。验证结论：" + capa.getVerifyNote());
        } else {
            // 退回执行中。closeEffective 保留 FALSE 作为「上一次验证没过」的痕迹，
            // 下次验证会被覆盖，但它在下一次验证之前一直是「这条措施被打回过」的证据
            capa.setStatus(QcCapa.STATUS_DOING);
            notifyOwner(capa, "你的纠正措施验证没通过",
                    "「" + capa.getTitle() + "」判为无效：" + capa.getVerifyNote()
                            + "。措施还不够，改完再提交验证——别直接关掉，关掉它下个月还会回来。");
        }
        touch(capa);
        return capa;
    }

    @Transactional
    public QcCapa cancel(Long id, String reason) {
        QcCapa capa = require(id);
        CapaRules.Check c = CapaRules.checkCancel(capa.getStatus(), reason);
        if (!c.ok()) {
            throw BizException.of(c.message());
        }
        capa.setStatus(QcCapa.STATUS_CANCELLED);
        capa.setCancelReason(reason.trim());
        touch(capa);
        return capa;
    }

    // ------------------------------------------------------------------ 再犯检测

    /**
     * 这个毛病以前出现过几次、有没有关过措施。
     *
     * <p>建措施之前先看一眼，比建完之后再想「好像以前也修过」有用得多。返回里最要紧的是
     * {@code closedEffective}：上一次判过「有效」而现在又来了，说明当时打偏了根因，
     * 这次别再照着上次那套做一遍。
     */
    public Map<String, Object> recurrence(Long productId, String defectKey) {
        Map<String, Object> out = new HashMap<>();
        if (productId == null || defectKey == null || defectKey.isBlank()) {
            out.put("occ", 0);
            out.put("batches", 0);
            out.put("closedCapas", List.of());
            out.put("advice", null);
            return out;
        }

        String from = LocalDate.now().minusDays(HISTORY_DAYS)
                .atStartOfDay().atOffset(BIZ_OFFSET).toString();
        Map<String, Object> hist = capaMapper.defectHistory(productId, defectKey, from);
        long occ = hist == null ? 0 : toLong(hist.get("occ"));
        long batches = hist == null ? 0 : toLong(hist.get("batches"));
        out.put("occ", occ);
        out.put("qty", hist == null ? 0 : toLong(hist.get("qty")));
        out.put("batches", batches);
        out.put("lastAt", hist == null ? null : hist.get("last_at"));

        List<QcCapa> closed = capaMapper.selectList(new LambdaQueryWrapper<QcCapa>()
                .eq(QcCapa::getProductId, productId)
                .eq(QcCapa::getDefectCode, defectKey)
                .eq(QcCapa::getStatus, QcCapa.STATUS_CLOSED)
                .orderByDesc(QcCapa::getClosedAt));
        List<Map<String, Object>> closedList = new ArrayList<>();
        boolean anyEffective = false;
        for (QcCapa x : closed) {
            Map<String, Object> m = new HashMap<>();
            m.put("capaNo", x.getCapaNo());
            m.put("title", x.getTitle());
            m.put("closedAt", x.getClosedAt());
            m.put("effective", x.getCloseEffective());
            m.put("verifyNote", x.getVerifyNote());
            closedList.add(m);
            if (Boolean.TRUE.equals(x.getCloseEffective())) {
                anyEffective = true;
            }
        }
        out.put("closedCapas", closedList);

        String advice;
        if (anyEffective) {
            advice = "这条不良以前关过措施、还判过「验证有效」，现在又出现——说明当时打偏了根因。"
                    + "这次别再照着上一套做一遍，先看看它到底在什么条件下才发生";
        } else if (occ >= 3 && batches >= 2) {
            advice = "最近 " + HISTORY_DAYS + " 天出现 " + occ + " 次、涉及 " + batches
                    + " 个批次。跨批次反复，说明不是某一批的偶发问题，值得查根因";
        } else if (occ >= 3) {
            advice = "最近 " + HISTORY_DAYS + " 天出现 " + occ + " 次，但都挤在同一批里。"
                    + "这更像是这一批的来料或设备问题，先查批次，不急着一层层上措施";
        } else {
            advice = "最近 " + HISTORY_DAYS + " 天出现 " + occ + " 次，属于偶发。"
                    + "也可以先只做纠正（把这批处理掉），攒够次数再上措施";
        }
        out.put("advice", advice);
        return out;
    }

    // ------------------------------------------------------------------ 查询

    public Page<QcCapa> page(long current, long size, String status, String keyword,
                             Boolean overdue, String owner) {
        LambdaQueryWrapper<QcCapa> w = new LambdaQueryWrapper<>();
        String s = blankToNull(status);
        if (s != null) {
            w.eq(QcCapa::getStatus, s);
        }
        String o = blankToNull(owner);
        if (o != null) {
            w.eq(QcCapa::getOwner, o);
        }
        if (Boolean.TRUE.equals(overdue)) {
            // 超期只对还开着的有意义：关闭日之后当然超过了要求完成日，那是历史不是拖延
            w.lt(QcCapa::getDueDate, LocalDate.now())
                    .in(QcCapa::getStatus, QcCapa.STATUS_OPEN, QcCapa.STATUS_DOING, QcCapa.STATUS_VERIFYING);
        }
        String k = blankToNull(keyword);
        if (k != null) {
            w.and(q -> q.like(QcCapa::getCapaNo, k)
                    .or().like(QcCapa::getTitle, k)
                    .or().like(QcCapa::getOwner, k)
                    .or().like(QcCapa::getSourceRef, k)
                    .or().like(QcCapa::getDefectCode, k));
        }
        // 还开着的排前面，同状态按期限升序：快到期的先看见
        w.last("ORDER BY CASE status WHEN 'VERIFYING' THEN 0 WHEN 'DOING' THEN 1 WHEN 'OPEN' THEN 2"
                + " ELSE 3 END, due_date NULLS LAST, id DESC");
        Page<QcCapa> p = capaMapper.selectPage(new Page<>(current, size), w);
        fill(p.getRecords());
        return p;
    }

    /** 只取记录、不做视图回填与再犯计算。权限判断这类地方用它，别顺手调 detail */
    public QcCapa get(Long id) {
        return require(id);
    }

    public QcCapa detail(Long id) {        QcCapa capa = require(id);
        fill(List.of(capa));
        if (capa.getProductId() != null && capa.getDefectCode() != null) {
            Map<String, Object> rec = recurrence(capa.getProductId(), capa.getDefectCode());
            Object advice = rec.get("advice");
            long closed = ((List<?>) rec.get("closedCapas")).size();
            if (closed > 0 || toLong(rec.get("occ")) > 0) {
                capa.setRecurrenceNote(String.valueOf(advice));
            }
        }
        return capa;
    }

    /** 待办用：超期排最前，其次按期限。只给还开着的 */
    public List<QcCapa> pending(int limit) {
        Page<QcCapa> p = capaMapper.selectPage(new Page<>(1, Math.max(1, limit)),
                new LambdaQueryWrapper<QcCapa>()
                        .in(QcCapa::getStatus, QcCapa.STATUS_OPEN, QcCapa.STATUS_DOING, QcCapa.STATUS_VERIFYING)
                        .last("ORDER BY (due_date IS NOT NULL AND due_date < CURRENT_DATE) DESC,"
                                + " due_date NULLS LAST, id DESC"));
        fill(p.getRecords());
        return p.getRecords();
    }

    public long countOpen() {
        return capaMapper.selectCount(new LambdaQueryWrapper<QcCapa>()
                .in(QcCapa::getStatus, QcCapa.STATUS_OPEN, QcCapa.STATUS_DOING, QcCapa.STATUS_VERIFYING));
    }

    public long countOverdue() {
        return capaMapper.selectCount(new LambdaQueryWrapper<QcCapa>()
                .in(QcCapa::getStatus, QcCapa.STATUS_OPEN, QcCapa.STATUS_DOING, QcCapa.STATUS_VERIFYING)
                .lt(QcCapa::getDueDate, LocalDate.now()));
    }

    public List<Map<String, Object>> openByOwner() {
        return capaMapper.openByOwner();
    }

    // ------------------------------------------------------------------ 内部

    private QcCapa require(Long id) {
        QcCapa capa = id == null ? null : capaMapper.selectById(id);
        if (capa == null) {
            throw BizException.of("措施不存在：" + id);
        }
        return capa;
    }

    private void touch(QcCapa capa) {
        capa.setUpdatedBy(UserContext.username());
        capa.setUpdatedAt(LocalDateTime.now());
        capaMapper.updateById(capa);
        // 落库之后再回填视图字段（状态文案、超期、开了几天）。放在 update 之后是为了
        // 让写库的语句保持最简；这几个字段本来也不入库
        fill(List.of(capa));
    }

    private void fill(List<QcCapa> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> pids = new LinkedHashSet<>();
        for (QcCapa c : rows) {
            if (c.getProductId() != null) {
                pids.add(c.getProductId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!pids.isEmpty()) {
            for (QcProduct p : productMapper.selectBatchIds(pids)) {
                names.put(p.getId(), p.getProductName());
            }
        }
        LocalDate today = LocalDate.now();
        for (QcCapa c : rows) {
            c.setProductName(names.get(c.getProductId()));
            c.setStatusText(CapaRules.statusText(c.getStatus()));
            c.setOverdue(CapaRules.overdue(c.getDueDate(), c.getStatus(), today));
            c.setOverdueDays(CapaRules.overdueDays(c.getDueDate(), today));
            c.setDaysOpen(c.getCreatedAt() == null ? null
                    : (int) ChronoUnit.DAYS.between(c.getCreatedAt().toLocalDate(), today));
        }
    }

    private void notifyOwner(QcCapa capa, String title, String content) {
        try {
            noticeService.push(capa.getOwner(), NoticeService.TYPE_CAPA, title, content,
                    "CAPA", capa.getId(), capa.getCapaNo());
        } catch (Exception e) {
            // 通知失败不能把建单/验证一起回滚：站内通知是第二通道，
            // 记录本身（这张措施）才是权威
            log.warn("CAPA 通知发送失败：{}", capa.getCapaNo(), e);
        }
    }

    private static String appendRemark(String old, String add) {
        if (old == null || old.isBlank()) {
            return add;
        }
        return old + "\n" + add;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
