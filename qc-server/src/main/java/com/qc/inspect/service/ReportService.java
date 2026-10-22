package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.dto.ImageView;
import com.qc.inspect.entity.*;
import com.qc.inspect.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 检验报告 / 合格证的数据装配。
 * <p>
 * 报告只读，全部取建单时冻结的快照（明细里已存了标准限值），
 * 所以标准后来改版也不会让历史报告变样。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final QcInspectionMapper inspectionMapper;
    private final QcInspectionLineMapper lineMapper;
    private final QcInspectionLogMapper logMapper;
    private final QcBatchMapper batchMapper;
    private final QcProductMapper productMapper;
    private final QcDefectMapper defectMapper;
    private final ImageService imageService;

    public Map<String, Object> inspectionReport(Long inspectionId) {
        QcInspection ins = inspectionMapper.selectById(inspectionId);
        if (ins == null) {
            throw BizException.of("检验单不存在：" + inspectionId);
        }

        QcBatch batch = ins.getBatchId() == null ? null : batchMapper.selectById(ins.getBatchId());
        QcProduct product = ins.getProductId() == null ? null
                : productMapper.selectById(ins.getProductId());

        List<QcInspectionLine> lines = lineMapper.selectList(
                Wrappers.<QcInspectionLine>lambdaQuery()
                        .eq(QcInspectionLine::getInspectionId, inspectionId)
                        .orderByAsc(QcInspectionLine::getPieceNo)
                        .orderByAsc(QcInspectionLine::getSeq));

        // 按件分组，报告上每件一段
        Map<Integer, List<QcInspectionLine>> byPiece = new TreeMap<>();
        for (QcInspectionLine l : lines) {
            byPiece.computeIfAbsent(l.getPieceNo() == null ? 1 : l.getPieceNo(), k -> new ArrayList<>()).add(l);
        }
        List<Map<String, Object>> pieces = new ArrayList<>();
        for (var e : byPiece.entrySet()) {
            long ng = e.getValue().stream().filter(l -> "FAIL".equals(l.getResult())).count();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pieceNo", e.getKey());
            p.put("ngCount", ng);
            p.put("result", ng > 0 ? "FAIL" : "PASS");
            p.put("rows", e.getValue());
            pieces.add(p);
        }

        List<QcDefect> defects = defectMapper.selectList(Wrappers.<QcDefect>lambdaQuery()
                .eq(QcDefect::getInspectionId, inspectionId)
                .orderByAsc(QcDefect::getId));

        List<ImageView> images = imageService.list("INSPECTION", inspectionId);

        List<QcInspectionLog> logs = logMapper.selectList(Wrappers.<QcInspectionLog>lambdaQuery()
                .eq(QcInspectionLog::getInspectionId, inspectionId)
                .orderByAsc(QcInspectionLog::getId));

        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("result", ins.getResult());
        conclusion.put("pass", "PASS".equals(ins.getResult()));
        conclusion.put("reason", ins.getJudgeReason());
        conclusion.put("sampleQty", ins.getSampleQty());
        conclusion.put("acceptQty", ins.getAcceptQty());
        conclusion.put("passedQty", ins.getPassedQty());
        conclusion.put("failedQty", ins.getFailedQty());
        conclusion.put("ngCount", ins.getNgCount());
        conclusion.put("criticalNgCount", ins.getCriticalNgCount());
        // 未提交的单据不能当作报告使用，前端要显式提示
        conclusion.put("final", !"DRAFT".equals(ins.getStatus()));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("inspection", ins);
        r.put("batch", batch);
        r.put("product", product);
        r.put("pieces", pieces);
        r.put("defects", defects);
        r.put("images", images);
        r.put("conclusion", conclusion);
        r.put("logs", logs);
        r.put("printedBy", UserContext.username());
        r.put("printedAt", LocalDateTime.now());
        // 复检单的报告要印出来源单号：报告是给客户和审核员看的，他们不认内部 id
        if (ins.getRecheckOfId() != null) {
            QcInspection origin = inspectionMapper.selectById(ins.getRecheckOfId());
            if (origin != null) {
                r.put("recheckOfNo", origin.getInspectionNo());
            }
        }
        return r;
    }
}
