package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.dto.AuditQuery;
import com.qc.inspect.dto.ComplaintQuery;
import com.qc.inspect.dto.InspectionQuery;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcComplaint;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcInspectionLine;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcInspectionLineMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CSV 导出。
 *
 * <p>为什么不用 POI 出 Excel：这三张表本质是台账，交付出去就是被人丢进 Excel 再二次透视的。
 * CSV 不带样式反而更好用，也省掉一个几十兆的依赖。真需要带格式的表单（检验报告）走 ReportService 打印。
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QcInspectionMapper inspectionMapper;
    private final QcInspectionLineMapper lineMapper;
    private final QcDefectMapper defectMapper;
    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;
    private final AuditService auditService;
    private final ComplaintService complaintService;

    // ==================== 检验单台账 ====================

    public String inspectionLedger(InspectionQuery query) {
        List<QcInspection> list = inspectionMapper.selectList(
                query.toWrapper().orderByDesc(QcInspection::getId));
        if (list.isEmpty()) {
            throw BizException.of("没有符合条件的检验单可导出");
        }

        Map<Long, QcBatch> batches = mapById(list.stream().map(QcInspection::getBatchId).distinct().toList(),
                batchMapper::selectBatchIds, QcBatch::getId);
        Map<Long, QcProduct> products = mapById(list.stream().map(QcInspection::getProductId).distinct().toList(),
                productMapper::selectBatchIds, QcProduct::getId);

        Csv csv = new Csv();
        csv.header("检验单号", "批次号", "工单号", "产品", "检验类型", "抽样方式",
                "抽样件数", "允收数Ac", "合格件数", "不合格件数", "NG行数", "关键NG行数",
                "判定结论", "单据状态", "检验员", "检验时间", "审核人", "审核时间", "判定说明");

        for (QcInspection ins : list) {
            QcBatch batch = batches.get(ins.getBatchId());
            QcProduct product = products.get(ins.getProductId());
            csv.row(ins.getInspectionNo(),
                    batch == null ? null : batch.getBatchNo(),
                    batch == null ? null : batch.getWorkOrderNo(),
                    product == null ? null : product.getProductName(),
                    ins.getInspectType(),
                    ins.getSamplingType(),
                    ins.getSampleQty(),
                    ins.getAcceptQty(),
                    ins.getPassedQty(),
                    ins.getFailedQty(),
                    ins.getNgCount(),
                    ins.getCriticalNgCount(),
                    resultText(ins.getResult()),
                    statusText(ins.getStatus()),
                    ins.getInspectorName(),
                    fmt(ins.getInspectedAt()),
                    ins.getReviewedBy(),
                    fmt(ins.getReviewedAt()),
                    ins.getJudgeReason());
        }
        return csv.toString();
    }

    // ==================== 不良台账 ====================

    public String defectLedger(String status) {
        List<QcDefect> list = defectMapper.selectList(new LambdaQueryWrapper<QcDefect>()
                .eq(status != null && !status.isBlank(), QcDefect::getDispositionStatus, status)
                .orderByDesc(QcDefect::getId));
        if (list.isEmpty()) {
            throw BizException.of("没有符合条件的不良记录可导出");
        }

        Map<Long, QcInspection> inspections = mapById(
                list.stream().map(QcDefect::getInspectionId).distinct().toList(),
                inspectionMapper::selectBatchIds, QcInspection::getId);

        Csv csv = new Csv();
        csv.header("检验单号", "件号", "不良名称", "不良代码", "数量",
                "处置方式", "处置状态", "处置人", "处置时间", "让步理由", "让步授权人", "复检单号", "发现时间");

        for (QcDefect d : list) {
            QcInspection ins = inspections.get(d.getInspectionId());
            csv.row(ins == null ? null : ins.getInspectionNo(),
                    d.getPieceNo(),
                    d.getDefectName(),
                    d.getDefectCode(),
                    d.getDefectQty(),
                    dispositionText(d.getDisposition()),
                    defectStatusText(d.getDispositionStatus()),
                    d.getDispositionBy(),
                    fmt(d.getDispositionAt()),
                    d.getConcessionReason(),
                    d.getConcessionApprovedBy(),
                    d.getRecheckInspectionId(),
                    fmt(d.getCreatedAt()));
        }
        return csv.toString();
    }

    // ==================== 检验明细 ====================

    public String inspectionLines(Long inspectionId) {
        QcInspection ins = inspectionMapper.selectById(inspectionId);
        if (ins == null) {
            throw BizException.of("检验单不存在：" + inspectionId);
        }
        List<QcInspectionLine> lines = lineMapper.selectList(new LambdaQueryWrapper<QcInspectionLine>()
                .eq(QcInspectionLine::getInspectionId, inspectionId)
                .orderByAsc(QcInspectionLine::getPieceNo)
                .orderByAsc(QcInspectionLine::getSeq));
        if (lines.isEmpty()) {
            throw BizException.of("该检验单还没有明细可导出");
        }

        Csv csv = new Csv();
        csv.header("检验单号", "件号", "分组", "检验项", "数据类型", "测量方式",
                "下限", "上限", "单位", "实测值", "合格值(布尔)", "量具", "检验结果", "判定说明");

        for (QcInspectionLine l : lines) {
            csv.row(ins.getInspectionNo(),
                    l.getPieceNo(),
                    l.getGroupName(),
                    l.getItemName(),
                    valueTypeText(l.getValueType()),
                    "MULTIPLE".equals(l.getValueMode()) ? "多点(" + l.getPointCount() + ")" : "单值",
                    plain(l.getMinValue()),
                    plain(l.getMaxValue()),
                    l.getUnit(),
                    measuredText(l),
                    l.getBoolPassValue() == null ? null : (l.getBoolPassValue() ? "有" : "无"),
                    l.getGaugeCode(),
                    resultText(l.getResult()),
                    l.getJudgeReason());
        }
        return csv.toString();
    }

    // ==================== 帕累托 ====================

    /**
     * 不良帕累托导出。
     *
     * <p>帕累托的意义在于「少数原因造成多数问题」，所以导出时必须带累计占比——
     * 只有占比没有累计，拿到 Excel 里还得自己算一遍，那就白导了。
     */
    public String pareto(Long productId, String from) {
        List<Map<String, Object>> list = defectMapper.pareto(productId, from);
        if (list.isEmpty()) {
            throw BizException.of("没有不良数据可导出");
        }

        Csv csv = new Csv();
        csv.header("不良代码", "不良名称", "不良数量", "发生次数", "占比%", "累计占比%");
        for (Map<String, Object> row : list) {
            csv.row(text(row.get("code")),
                    text(row.get("name")),
                    row.get("qty"),
                    row.get("occ"),
                    row.get("percent"),
                    row.get("cumulative_percent"));
        }
        return csv.toString();
    }

    // ==================== 日合格率 ====================

    /**
     * 近 30 天合格率导出。单级（整单）与件级两个口径都出：
     * 抽检场景下件级更准，单级留着跟历史报表对账。
     */
    public String dailyYield() {
        List<Map<String, Object>> list = inspectionMapper.statDaily();
        if (list.isEmpty()) {
            throw BizException.of("近 30 天没有可统计的检验单");
        }

        Csv csv = new Csv();
        csv.header("日期", "检验单数", "整单合格数", "单级合格率%", "受检件数", "合格件数", "件级合格率%");
        for (Map<String, Object> row : list) {
            long total = num(row.get("total"));
            long passCnt = num(row.get("pass_cnt"));
            long pieceTotal = num(row.get("piece_total"));
            long piecePass = num(row.get("piece_pass"));
            csv.row(row.get("day"),
                    total,
                    passCnt,
                    percent(passCnt, total),
                    pieceTotal,
                    piecePass,
                    percent(piecePass, pieceTotal));
        }
        return csv.toString();
    }

    // ==================== 内部方法 ====================

    private String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** 分母为 0 时返回空串而不是 0：报表里「0%」和「没有数据」是两件事 */
    private String percent(long part, long total) {
        if (total <= 0) {
            return "";
        }
        return BigDecimal.valueOf(part * 100.0 / total)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String measuredText(QcInspectionLine l) {
        if (l.getMeasuredText() != null && !l.getMeasuredText().isBlank()) {
            return l.getMeasuredText();
        }
        String raw = l.getMeasuredValues();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 存的是 JSON 数组字符串，导出时去掉引号和方括号，保持人眼可读
        String s = raw.replaceAll("^\\[|]$", "").replace("\"", "").trim();
        return s.isEmpty() ? null : s;
    }

    private <T> Map<Long, T> mapById(List<Long> ids, Function<List<Long>, List<T>> loader,
                                     Function<T, Long> idOf) {
        List<Long> clean = ids.stream().filter(java.util.Objects::nonNull).toList();
        if (clean.isEmpty()) {
            return Map.of();
        }
        return loader.apply(new ArrayList<>(clean)).stream()
                .collect(Collectors.toMap(idOf, Function.identity(), (a, b) -> a));
    }

    // ==================== 操作日志 ====================

    /**
     * 操作日志导出。内审时最常见的动作就是「把上季度作废/解锁的记录打一份出来」，
     * 一页一页截图不现实。
     */
    public String auditLedger(AuditQuery query) {
        List<AuditService.AuditRow> list = auditService.list(query, 50000);
        if (list.isEmpty()) {
            throw BizException.of("没有符合条件的操作日志可导出");
        }

        Csv csv = new Csv();
        csv.header("发生时间", "动作", "检验单号", "批次号", "产品",
                "原状态", "新状态", "操作人", "理由/意见", "结果摘要");

        for (AuditService.AuditRow r : list) {
            csv.row(fmt(r.createdAt()),
                    actionText(r.action()),
                    r.inspectionNo(),
                    r.batchNo(),
                    r.productName(),
                    statusText(r.fromStatus()),
                    statusText(r.toStatus()),
                    r.operator(),
                    r.reason(),
                    r.summary());
        }
        return csv.toString();
    }

    // ==================== 客户投诉 ====================

    /**
     * 客诉台账导出。
     *
     * <p>体系审核每年必问的三件事：投诉几起、关闭了几起、根因分布是什么。
     * 这三件事用屏幕翻页回答不了，必须能一次性打出来。
     * 根因、措施、责任方三列留着空白也是有用的信号——那是还没闭环的单。
     */
    public String complaintLedger(ComplaintQuery query) {
        List<QcComplaint> list = complaintService.list(query, 50000);
        if (list.isEmpty()) {
            throw BizException.of("没有符合条件的客户投诉可导出");
        }

        Csv csv = new Csv();
        csv.header("客诉单号", "客诉日期", "客户", "批次号", "产品", "出货单号",
                "类别", "严重度", "涉及数量", "问题描述", "处理状态",
                "根本原因", "纠正预防措施", "责任归属",
                "登记人", "关闭人", "关闭时间", "备注");

        for (QcComplaint c : list) {
            csv.row(c.getComplaintNo(),
                    c.getComplaintDate() == null ? null : c.getComplaintDate().toString(),
                    c.getCustomer(),
                    c.getBatchNo(),
                    c.getProductName(),
                    c.getShipmentNo(),
                    categoryText(c.getCategory()),
                    severityText(c.getSeverity()),
                    c.getQty(),
                    c.getDescription(),
                    complaintStatusText(c.getStatus()),
                    c.getRootCause(),
                    c.getAction(),
                    c.getResponsibility(),
                    c.getCreatedBy(),
                    c.getClosedBy(),
                    fmt(c.getClosedAt()),
                    c.getRemark());
        }
        return csv.toString();
    }

    private String categoryText(String c) {
        if (c == null) {
            return null;
        }
        return switch (c) {
            case "APP" -> "外观";
            case "SIZE" -> "尺寸";
            case "PERF" -> "性能";
            case "PACK" -> "包装";
            case "MARK" -> "标识";
            case "OTHER" -> "其他";
            default -> c;
        };
    }

    private String severityText(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "HIGH" -> "严重";
            case "MEDIUM" -> "一般";
            case "LOW" -> "轻微";
            default -> s;
        };
    }

    private String complaintStatusText(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "OPEN" -> "待处理";
            case "PROCESSING" -> "处理中";
            case "CLOSED" -> "已关闭";
            default -> s;
        };
    }

    private String actionText(String a) {
        if (a == null) {
            return null;
        }
        return switch (a) {
            case "CREATE" -> "建单";
            case "SUBMIT" -> "提交";
            case "REVIEW" -> "审核通过";
            case "REJECT" -> "审核退回";
            case "REOPEN" -> "解锁重开";
            case "CLOSE" -> "归档";
            case "VOID" -> "作废";
            default -> a;
        };
    }

    private String fmt(LocalDateTime t) {
        return t == null ? null : t.format(DT);
    }

    private String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private String resultText(String r) {
        if (r == null) {
            return null;
        }
        return switch (r) {
            case "PASS" -> "合格";
            case "FAIL" -> "不合格";
            case "NA" -> "未判定";
            case "PENDING" -> "待判定";
            default -> r;
        };
    }

    private String statusText(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "DRAFT" -> "草稿";
            case "SUBMITTED" -> "待审核";
            case "CLOSED" -> "已归档";
            case "VOID" -> "已作废";
            default -> s;
        };
    }

    private String dispositionText(String d) {
        if (d == null) {
            return null;
        }
        return switch (d) {
            case "REWORK" -> "返工";
            case "SCRAP" -> "报废";
            case "CONCESSION" -> "让步接收";
            case "RETURN" -> "退货";
            default -> d;
        };
    }

    private String defectStatusText(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "PENDING" -> "待处理";
            case "PROCESSING" -> "处理中";
            case "DONE" -> "已完成";
            case "WAIVED" -> "已让步";
            default -> s;
        };
    }

    private String valueTypeText(String t) {
        if (t == null) {
            return null;
        }
        return switch (t) {
            case "NUMBER" -> "数值";
            case "ENUM" -> "枚举";
            case "BOOL" -> "有/无";
            case "TEXT" -> "文本";
            default -> t;
        };
    }

    /**
     * CSV 拼装。
     *
     * <p>两件必须做对的事：
     * 一是转义（含逗号、引号、换行的字段要整体加引号，内部引号翻倍），
     * 二是防公式注入（以 = + @ 开头的值在 Excel 里会被当公式执行，
     * 前面补一个单引号让它保持纯文本）。第二点容易被忽略，但导出的是别人填的备注。
     */
    private static final class Csv {

        private final StringBuilder sb = new StringBuilder(4096);

        void header(String... cols) {
            row((Object[]) cols);
        }

        void row(Object... cols) {
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell(cols[i]));
            }
            sb.append("\r\n");
        }

        private String cell(Object v) {
            if (v == null) {
                return "";
            }
            String s = v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
            s = guardFormula(s);
            if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
                return '"' + s.replace("\"", "\"\"") + '"';
            }
            return s;
        }

        private String guardFormula(String s) {
            if (s.isEmpty()) {
                return s;
            }
            char c = s.charAt(0);
            boolean dangerous = c == '=' || c == '+' || c == '@' || c == '\t';
            // 负号只有在后面不是数字时才可能是攻击载荷，避免把 -0.02 这类正常值也加前缀
            if (!dangerous && c == '-' && (s.length() < 2 || !Character.isDigit(s.charAt(1)))) {
                dangerous = true;
            }
            return dangerous ? "'" + s : s;
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
