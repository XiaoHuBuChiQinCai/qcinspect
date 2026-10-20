package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcGaugeMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 待办聚合。
 *
 * <p>质检系统的用户不是坐在电脑前等人派活的：检验员在产线、审核员在巡线、计量员在管量具。
 * 他们打开 App 时只想知道「现在有几件事等我做」。这个类就是回答这一句话的，
 * 五个数字一次给全，前端不必为角标发五次请求。
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li>待审核只算 {@code SUBMITTED}。草稿是检验员自己的活，不该出现在审核员的待办里</li>
 *   <li>量具超期单独立一项。超期量具量出来的数据，体系审核直接判无效——它比「有几张单待审」更要紧</li>
 *   <li>退回待办按「当前是草稿 + 历史上出现过 REJECT」判定，改完重交后自然消失，不需要已读标记</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TodoService {

    private final QcInspectionMapper inspectionMapper;
    private final QcDefectMapper defectMapper;
    private final QcGaugeMapper gaugeMapper;
    private final InspectionViewService viewService;

    /** 首页角标 */
    public Map<String, Object> summary(String operator) {
        LocalDate today = LocalDate.now();

        long pendingReview = inspectionMapper.selectCount(new LambdaQueryWrapper<QcInspection>()
                .eq(QcInspection::getStatus, "SUBMITTED"));
        long pendingDefect = defectMapper.selectCount(new LambdaQueryWrapper<QcDefect>()
                .in(QcDefect::getDispositionStatus, "PENDING", "PROCESSING"));
        long overdueGauge = gaugeMapper.selectCount(new LambdaQueryWrapper<QcGauge>()
                .eq(QcGauge::getStatus, "ACTIVE")
                .lt(QcGauge::getNextCalibrateDate, today));
        long myDraft = inspectionMapper.selectCount(new LambdaQueryWrapper<QcInspection>()
                .eq(QcInspection::getStatus, "DRAFT")
                .eq(QcInspection::getCreatedBy, operator));
        long myRejected = inspectionMapper.countRejectedDrafts(operator);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pendingReview", pendingReview);
        m.put("pendingDefect", pendingDefect);
        m.put("overdueGauge", overdueGauge);
        m.put("myDraft", myDraft);
        m.put("myRejected", myRejected);
        // 角标总数不含量具超期：它不是「单据」，混在一起会让人以为有几十张单没处理
        m.put("actionable", pendingReview + pendingDefect + myRejected);
        return m;
    }

    /** 待审核：已提交但还没人放行的单 */
    public List<QcInspection> pendingReviews() {
        List<QcInspection> list = inspectionMapper.selectList(new LambdaQueryWrapper<QcInspection>()
                .eq(QcInspection::getStatus, "SUBMITTED")
                .orderByAsc(QcInspection::getId));
        viewService.fill(list);
        return list;
    }

    /** 待处置不良：PENDING 与 PROCESSING 都算没闭环 */
    public List<Map<String, Object>> pendingDefects() {
        List<QcDefect> list = defectMapper.selectList(new LambdaQueryWrapper<QcDefect>()
                .in(QcDefect::getDispositionStatus, "PENDING", "PROCESSING")
                .orderByAsc(QcDefect::getId));
        if (list.isEmpty()) {
            return List.of();
        }

        Set<Long> ids = list.stream().map(QcDefect::getInspectionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QcInspection> inspections = ids.isEmpty() ? Map.of()
                : inspectionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(QcInspection::getId, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (QcDefect d : list) {
            QcInspection ins = inspections.get(d.getInspectionId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("inspectionId", d.getInspectionId());
            row.put("inspectionNo", ins == null ? null : ins.getInspectionNo());
            row.put("pieceNo", d.getPieceNo());
            row.put("defectCode", d.getDefectCode());
            row.put("defectName", d.getDefectName());
            row.put("defectQty", d.getDefectQty());
            row.put("disposition", d.getDisposition());
            row.put("dispositionStatus", d.getDispositionStatus());
            row.put("dispositionNote", d.getDispositionNote());
            row.put("createdAt", d.getCreatedAt());
            rows.add(row);
        }
        return rows;
    }

    /** 我的：草稿（含被退回的）与被退回清单 */
    public Map<String, Object> mine(String operator) {
        List<QcInspection> drafts = inspectionMapper.selectList(new LambdaQueryWrapper<QcInspection>()
                .eq(QcInspection::getStatus, "DRAFT")
                .eq(QcInspection::getCreatedBy, operator)
                .orderByDesc(QcInspection::getId));
        List<QcInspection> rejected = inspectionMapper.rejectedDrafts(operator);
        viewService.fill(drafts);
        viewService.fill(rejected);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drafts", drafts);
        m.put("rejected", rejected);
        return m;
    }

    /** 超期未校准的量具，附超期天数 */
    public List<Map<String, Object>> overdueGauges() {
        LocalDate today = LocalDate.now();
        List<QcGauge> list = gaugeMapper.selectList(new LambdaQueryWrapper<QcGauge>()
                .eq(QcGauge::getStatus, "ACTIVE")
                .lt(QcGauge::getNextCalibrateDate, today)
                .orderByAsc(QcGauge::getNextCalibrateDate));
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (QcGauge g : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("gaugeCode", g.getGaugeCode());
            row.put("gaugeName", g.getGaugeName());
            row.put("calibrateDate", g.getCalibrateDate());
            row.put("nextCalibrateDate", g.getNextCalibrateDate());
            row.put("overdueDays", ChronoUnit.DAYS.between(g.getNextCalibrateDate(), today));
            row.put("ownerDept", g.getOwnerDept());
            rows.add(row);
        }
        return rows;
    }
}
