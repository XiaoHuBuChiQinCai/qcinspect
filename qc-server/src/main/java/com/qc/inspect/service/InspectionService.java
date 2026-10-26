package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.dto.InspectionSubmitReq;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcInspectionLine;
import com.qc.inspect.entity.QcInspectionLog;
import com.qc.inspect.entity.QcItem;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import com.qc.inspect.judge.EnumOption;
import com.qc.inspect.judge.InspectionJudge;
import com.qc.inspect.judge.InspectionOutcome;
import com.qc.inspect.judge.ItemSpec;
import com.qc.inspect.judge.JudgeTarget;
import com.qc.inspect.judge.JudgedItem;
import com.qc.inspect.judge.ValueMode;
import com.qc.inspect.judge.ValueType;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcInspectionLineMapper;
import com.qc.inspect.mapper.QcInspectionLogMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
import com.qc.inspect.mapper.QcItemMapper;
import com.qc.inspect.mapper.QcStandardItemMapper;
import com.qc.inspect.mapper.QcStandardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检验单核心服务。
 *
 * <p>三件事：建单快照（按件展开）、提交判定（两层）、状态锁与留痕。
 * <p>状态机：DRAFT → SUBMITTED →（审核通过）CLOSED；SUBMITTED 可 REOPEN 回 DRAFT，
 * 也可被审核退回回 DRAFT；DRAFT/SUBMITTED 可 VOID。
 * 已提交的单据不允许原地改，一律走 reopen 并写日志——质量记录一旦能被静默修改，就没有证据价值。
 */
@Service
@RequiredArgsConstructor
public class InspectionService {

    /** 单次建单允许的最大件数，防止误填 9999 把明细表写爆 */
    private static final int MAX_SAMPLE_QTY = 200;

    private final QcBatchMapper batchMapper;
    private final QcStandardMapper standardMapper;
    private final QcStandardItemMapper standardItemMapper;
    private final QcItemMapper itemMapper;
    private final QcInspectionMapper inspectionMapper;
    private final QcInspectionLineMapper lineMapper;
    private final QcDefectMapper defectMapper;
    private final QcInspectionLogMapper logMapper;
    private final GaugeService gaugeService;
    private final NoticeService noticeService;
    private final SamplingPlanService samplingPlanService;
    private final ObjectMapper objectMapper;

    // ==================== 建单（含标准快照） ====================

    @Transactional
    public Map<String, Object> create(Long batchId, String inspectorName, Integer sampleQty, String operator) {
        QcBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BizException.of("批次不存在：" + batchId);
        }

        QcStandard standard = standardMapper.selectOne(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, batch.getProductId())
                .eq(QcStandard::getStatus, "ACTIVE"));
        if (standard == null) {
            throw BizException.of("该产品没有生效中的检验标准，请先在 PC 端发布标准");
        }

        List<QcStandardItem> stdItems = standardItemMapper.selectList(
                new LambdaQueryWrapper<QcStandardItem>()
                        .eq(QcStandardItem::getStandardId, standard.getId())
                        .orderByAsc(QcStandardItem::getSeq));
        if (stdItems.isEmpty()) {
            throw BizException.of("检验标准未配置任何检验项");
        }

        // 抽样量与允收数由标准决定：固定抽样取标准上写死的值，AQL 模式按批量查国标表
        SamplingResult sampling = samplingPlanService.resolve(standard, batch.getQty(), sampleQty);
        int qty = sampling.sampleQty();
        if (qty > MAX_SAMPLE_QTY) {
            throw BizException.of("这一单要验 " + qty + " 件，超过单次上限 " + MAX_SAMPLE_QTY
                    + " 件。请把批次拆小，或把标准的抽样方案改成按 AQL 抽检");
        }
        int acceptQty = sampling.acceptQty();

        QcInspection ins = new QcInspection();
        ins.setInspectionNo(nextNo());
        ins.setBatchId(batchId);
        ins.setStandardId(standard.getId());
        ins.setStandardVersion(standard.getVersion());
        ins.setProductId(batch.getProductId());
        ins.setInspectType(batch.getInspectType());
        ins.setSamplingType(standard.getSamplingType());
        ins.setSampleQty(qty);
        // 允收数同期快照：标准日后改 Ac，不影响已建的单
        ins.setAcceptQty(acceptQty);
        // 抽样依据一并冻在单上：一年后回看，要能答出「当时凭什么抽这么多件」
        ins.setLotQty(sampling.lotQty());
        ins.setSamplingBasis(sampling.basis());
        // 检验员是质量记录的责任人，不能由前端随便填：已登录时以登录态为准
        ins.setInspectorName(UserContext.current() != null ? UserContext.username()
                : (inspectorName == null || inspectorName.isBlank() ? UserContext.ANONYMOUS : inspectorName));
        ins.setStatus("DRAFT");
        ins.setResult("NA");
        ins.setPassedQty(0);
        ins.setFailedQty(0);
        ins.setNgCount(0);
        ins.setCriticalNgCount(0);
        ins.setCreatedBy(operator);
        ins.setCreatedAt(LocalDateTime.now());
        inspectionMapper.insert(ins);

        // 按件展开：件 × 项。抽 5 件 8 项 → 40 行，每行只代表「第 i 件的第 j 项」
        int seq = 0;
        for (int piece = 1; piece <= qty; piece++) {
            for (QcStandardItem si : stdItems) {
                QcItem item = itemMapper.selectById(si.getItemId());
                if (item == null) {
                    continue;
                }
                QcInspectionLine line = new QcInspectionLine();
                line.setInspectionId(ins.getId());
                line.setPieceNo(piece);
                line.setItemId(item.getId());
                // 快照：自此与标准解绑
                line.setItemCode(item.getItemCode());
                line.setItemName(item.getItemName());
                line.setGroupName(firstNonNull(si.getGroupName(), item.getGroupName()));
                line.setValueType(item.getValueType());
                line.setValueMode(si.getValueMode());
                line.setPointCount(si.getPointCount() == null ? 1 : si.getPointCount());
                line.setUnit(item.getUnit());
                line.setMinValue(si.getMinValue() != null ? si.getMinValue() : item.getDefaultMin());
                line.setMaxValue(si.getMaxValue() != null ? si.getMaxValue() : item.getDefaultMax());
                line.setOptionsJson(si.getOptionsJson() != null ? si.getOptionsJson() : item.getOptionsJson());
                // 布尔合格值三层回退：标准项覆盖 → 检验项主数据 → 默认 true
                line.setBoolPassValue(si.getBoolPassValue() != null
                        ? si.getBoolPassValue()
                        : (item.getBoolPassValue() != null ? item.getBoolPassValue() : Boolean.TRUE));
                line.setIsCritical(Boolean.TRUE.equals(si.getIsCritical()));
                line.setSeq(seq += 10);
                // 量具提示也快照：移动端按项推荐量具，不靠检验员在台账里凭记忆翻
                line.setMeasureTool(item.getMeasureTool());
                line.setResult("PENDING");
                line.setCreatedAt(LocalDateTime.now());
                lineMapper.insert(line);
            }
        }

        batch.setStatus("INSPECTING");
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);

        writeLog(ins.getId(), "CREATE", null, "DRAFT", operator,
                "抽样 " + qty + " 件，允收数 " + acceptQty + "，标准 v" + standard.getVersion(), null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspectionId", ins.getId());
        result.put("inspectionNo", ins.getInspectionNo());
        result.put("standardVersion", standard.getVersion());
        result.put("sampleQty", qty);
        result.put("acceptQty", acceptQty);
        result.put("lineCount", qty * stdItems.size());
        return result;
    }

    // ==================== 返工复检 ====================

    /**
     * 从一条不良记录开复检单。
     *
     * <p>为什么需要这个入口：V7 之前系统强制「返工完成后必须挂一张复检单」，
     * 却没有开复检单的地方——检验员只能去批次页手工开一张普通单，再回来抄单号。
     * 抄错一个数字，两批货的追溯就链错了。规则拦住了人，却没给人留出路，这不叫管控。
     *
     * <p>三个刻意与普通建单不同的取值：
     * <ul>
     *   <li><b>件数 = 该不良的返工件数</b>，不按 AQL 重抽。返工了 3 件就验 3 件；
     *       按批量重新抽 50 件，抽的是早已判过的那批，验了也不能证明返工有效</li>
     *   <li><b>Ac 固定 0</b>。这不是抽样验收，是返工有效性确认；给一个允收数，
     *       等于允许返工后还有不合格件流出去</li>
     *   <li><b>明细从原单复制</b>，不重新读 ACTIVE 标准。标准若已改版，
     *       复检和原单就不是同一套判据，两级结论没法对比，复检也就白做了</li>
     * </ul>
     *
     * <p>不改批次状态：复检只针对返工的几件，批次整体结论不因复检而回退。
     */
    @Transactional
    public Map<String, Object> createRecheck(Long defectId, String operator) {
        QcDefect defect = defectMapper.selectById(defectId);
        if (defect == null) {
            throw BizException.of("不良记录不存在：" + defectId);
        }
        if (defect.getRecheckInspectionId() != null) {
            // 作废的复检单不算数：开错了（件数、标准选错）本来就该作废重开，
            // 若照旧拦住，这条不良就永远开不出新单、也永远标不了完成——死锁。
            QcInspection stale = inspectionMapper.selectById(defect.getRecheckInspectionId());
            if (stale != null && !"VOID".equals(stale.getStatus())) {
                throw BizException.of("这条不良已经挂过复检单 " + stale.getInspectionNo()
                        + "（" + stale.getStatus() + "），请直接在那张单上继续填报，不要重复建单");
            }
        }
        if ("WAIVED".equals(defect.getDispositionStatus())) {
            throw BizException.of("让步接收的不良不需要复检，它本来就是特采放行的");
        }
        if (!"REWORK".equals(defect.getDisposition())) {
            throw BizException.of("只有返工处置才需要复检，这条不良的处置是「"
                    + (defect.getDisposition() == null ? "未处置" : defect.getDisposition()) + "」");
        }
        // 不良判出来时处置就已经预填成「返工」，但那是建议、不是已执行的动作。
        // 不校验这一步，就会出现「还没返工就开了复检单」——验的还是原来那批坏件，
        // 复检结论毫无意义，却会被当成返工有效的证据。
        if (!"PROCESSING".equals(defect.getDispositionStatus())) {
            throw BizException.of("这条不良的返工还没开始（当前「"
                    + ("PENDING".equals(defect.getDispositionStatus()) ? "待处理" : defect.getDispositionStatus())
                    + "」）。请先点「开始返工」，返工完成后再开复检单");
        }

        QcInspection origin = require(defect.getInspectionId());
        if (!"SUBMITTED".equals(origin.getStatus()) && !"CLOSED".equals(origin.getStatus())) {
            throw BizException.of("原单还没提交（当前 " + origin.getStatus()
                    + "），结论随时会变，现在开复检没有意义");
        }

        int qty = defect.getDefectQty() == null || defect.getDefectQty() <= 0 ? 1 : defect.getDefectQty();
        if (qty > MAX_SAMPLE_QTY) {
            throw BizException.of("这一条不良涉及 " + qty + " 件，超过单次上限 " + MAX_SAMPLE_QTY
                    + " 件。请按工位或班次拆成多张复检单，一次验完记不清哪件是哪件");
        }

        // 模板取原单第 1 件的整套明细行——原单每一件的检验项都一样，取一件即可
        List<QcInspectionLine> template = lineMapper.selectList(new LambdaQueryWrapper<QcInspectionLine>()
                .eq(QcInspectionLine::getInspectionId, origin.getId())
                .eq(QcInspectionLine::getPieceNo, 1)
                .orderByAsc(QcInspectionLine::getSeq));
        if (template.isEmpty()) {
            throw BizException.of("原单没有明细行，无法生成复检单");
        }

        QcInspection ins = new QcInspection();
        ins.setInspectionNo(nextNo());
        ins.setBatchId(origin.getBatchId());
        ins.setStandardId(origin.getStandardId());
        ins.setStandardVersion(origin.getStandardVersion());
        ins.setProductId(origin.getProductId());
        ins.setInspectType(origin.getInspectType());
        ins.setSamplingType("SAMPLE");
        ins.setSampleQty(qty);
        ins.setAcceptQty(0);
        ins.setLotQty(qty);
        ins.setSamplingBasis("返工复检：原单 " + origin.getInspectionNo() + " 第 " + defect.getPieceNo()
                + " 件「" + defect.getDefectName() + "」返工 " + qty + " 件，逐件全检，Ac=0");
        ins.setRecheckOfId(origin.getId());
        ins.setRecheckDefectId(defect.getId());
        ins.setInspectorName(UserContext.current() != null ? UserContext.username() : UserContext.ANONYMOUS);
        ins.setStatus("DRAFT");
        ins.setResult("NA");
        ins.setPassedQty(0);
        ins.setFailedQty(0);
        ins.setNgCount(0);
        ins.setCriticalNgCount(0);
        ins.setRemark("返工复检");
        ins.setCreatedBy(operator);
        ins.setCreatedAt(LocalDateTime.now());
        inspectionMapper.insert(ins);

        int seq = 0;
        for (int piece = 1; piece <= qty; piece++) {
            for (QcInspectionLine t : template) {
                QcInspectionLine line = new QcInspectionLine();
                line.setInspectionId(ins.getId());
                line.setPieceNo(piece);
                line.setItemId(t.getItemId());
                line.setItemCode(t.getItemCode());
                line.setItemName(t.getItemName());
                line.setGroupName(t.getGroupName());
                line.setValueType(t.getValueType());
                line.setValueMode(t.getValueMode());
                line.setPointCount(t.getPointCount());
                line.setUnit(t.getUnit());
                line.setMinValue(t.getMinValue());
                line.setMaxValue(t.getMaxValue());
                line.setOptionsJson(t.getOptionsJson());
                line.setBoolPassValue(t.getBoolPassValue());
                line.setIsCritical(t.getIsCritical());
                line.setSeq(seq += 10);
                line.setMeasureTool(t.getMeasureTool());
                line.setResult("PENDING");
                line.setCreatedAt(LocalDateTime.now());
                lineMapper.insert(line);
            }
        }

        defect.setRecheckInspectionId(ins.getId());
        defectMapper.updateById(defect);

        writeLog(ins.getId(), "CREATE", null, "DRAFT", operator,
                "由原单 " + origin.getInspectionNo() + " 的不良「" + defect.getDefectName()
                        + "」返工后开复检，共 " + qty + " 件全检，Ac=0", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspectionId", ins.getId());
        result.put("inspectionNo", ins.getInspectionNo());
        result.put("sampleQty", qty);
        result.put("acceptQty", 0);
        result.put("originInspectionId", origin.getId());
        result.put("originInspectionNo", origin.getInspectionNo());
        result.put("lineCount", qty * template.size());
        return result;
    }

    // ==================== 草稿暂存 ====================

    /**
     * 暂存已录入的实测值，不判定、不改状态。
     *
     * <p>抽 5 件 × 8 项 = 40 个输入框，没人能一口气填完。没有暂存，切件或误退出就全丢。
     * 这不是离线能力，只是把值落到服务端——草稿依旧是草稿。
     * 不写操作日志：暂存太频繁，日志会被刷爆，日志只记状态变化。
     */
    @Transactional
    public void saveDraft(Long inspectionId, List<InspectionSubmitReq.LineValue> inputLines, String operator) {
        QcInspection ins = require(inspectionId);
        assertDraft(ins);
        if (inputLines == null || inputLines.isEmpty()) {
            return;
        }

        Map<Long, InspectionSubmitReq.LineValue> inputMap = inputLines.stream()
                .collect(Collectors.toMap(InspectionSubmitReq.LineValue::lineId, Function.identity(), (a, b) -> b));

        List<QcInspectionLine> lines = lineMapper.selectList(new LambdaQueryWrapper<QcInspectionLine>()
                .eq(QcInspectionLine::getInspectionId, inspectionId));

        LocalDateTime now = LocalDateTime.now();
        for (QcInspectionLine line : lines) {
            InspectionSubmitReq.LineValue in = inputMap.get(line.getId());
            if (in == null) {
                continue;
            }
            line.setMeasuredValues(toJson(in.values() == null ? List.of() : in.values()));
            line.setMeasuredText(in.textValue());
            // 草稿阶段只记住选了哪把量具，有效性留到提交时硬校验
            if (in.gaugeId() != null) {
                QcGauge gauge = gaugeService.get(in.gaugeId());
                line.setGaugeId(in.gaugeId());
                line.setGaugeCode(gauge == null ? null : gauge.getGaugeCode());
            }
            line.setUpdatedAt(now);
            lineMapper.updateById(line);
        }
    }

    // ==================== 提交判定 ====================

    @Transactional
    public InspectionOutcome submit(Long inspectionId, List<InspectionSubmitReq.LineValue> inputLines, String operator) {
        QcInspection ins = require(inspectionId);
        assertDraft(ins);

        List<QcInspectionLine> lines = lineMapper.selectList(new LambdaQueryWrapper<QcInspectionLine>()
                .eq(QcInspectionLine::getInspectionId, inspectionId)
                .orderByAsc(QcInspectionLine::getSeq));

        Map<Long, InspectionSubmitReq.LineValue> inputMap = (inputLines == null ? List.<InspectionSubmitReq.LineValue>of() : inputLines)
                .stream()
                .collect(Collectors.toMap(InspectionSubmitReq.LineValue::lineId, Function.identity(), (a, b) -> b));

        List<JudgeTarget> targets = new ArrayList<>(lines.size());
        for (QcInspectionLine line : lines) {
            InspectionSubmitReq.LineValue in = inputMap.get(line.getId());
            List<String> measured = in == null || in.values() == null ? List.of() : in.values();
            String text = in == null ? null : in.textValue();

            line.setMeasuredValues(toJson(measured));
            line.setMeasuredText(text);
            // 量具校验：超期/报废的量具测出的数据不可采信，必须在提交时硬拦
            if (in != null && in.gaugeId() != null) {
                QcGauge gauge = gaugeService.assertUsable(in.gaugeId(), line.getItemName());
                line.setGaugeId(gauge.getId());
                line.setGaugeCode(gauge.getGaugeCode());
            }
            targets.add(new JudgeTarget(line.getId(), line.getItemId(),
                    line.getPieceNo() == null ? 1 : line.getPieceNo(),
                    line.getItemName(), Boolean.TRUE.equals(line.getIsCritical()),
                    toSpec(line), measured, text));
        }

        InspectionOutcome outcome = InspectionJudge.judge(targets, ins.getAcceptQty() == null ? 0 : ins.getAcceptQty());

        Map<Long, JudgedItem> judgedMap = outcome.items().stream()
                .collect(Collectors.toMap(JudgedItem::lineId, Function.identity(), (a, b) -> b));

        LocalDateTime now = LocalDateTime.now();
        for (QcInspectionLine line : lines) {
            JudgedItem j = judgedMap.get(line.getId());
            line.setResult(j != null && j.pass() ? "PASS" : "FAIL");
            line.setJudgeReason(j == null ? "未判定" : j.reason());
            line.setUpdatedAt(now);
            lineMapper.updateById(line);
        }

        // 重置不良记录，保证多次（解锁后）提交不重复堆积
        defectMapper.delete(new LambdaQueryWrapper<QcDefect>()
                .eq(QcDefect::getInspectionId, inspectionId));
        Map<Long, QcInspectionLine> lineById = lines.stream()
                .collect(Collectors.toMap(QcInspectionLine::getId, Function.identity()));
        for (JudgedItem j : outcome.items()) {
            if (j.pass()) {
                continue;
            }
            QcInspectionLine line = lineById.get(j.lineId());
            QcDefect d = new QcDefect();
            d.setInspectionId(inspectionId);
            d.setInspectionLineId(j.lineId());
            d.setPieceNo(j.pieceNo());
            d.setItemId(line == null ? null : line.getItemId());
            d.setDefectCode(line == null ? null : line.getItemCode());
            d.setDefectName(j.itemName());
            d.setDefectQty(1);
            d.setDisposition("REWORK");
            d.setDispositionNote(j.reason());
            d.setCreatedBy(operator);
            d.setCreatedAt(now);
            defectMapper.insert(d);
        }

        String from = ins.getStatus();
        ins.setStatus("SUBMITTED");
        ins.setResult(outcome.pass() ? "PASS" : "FAIL");
        ins.setPassedQty(outcome.passedQty());
        ins.setFailedQty(outcome.failedQty());
        ins.setNgCount(outcome.ngCount());
        ins.setCriticalNgCount(outcome.criticalNgCount());
        ins.setJudgeReason(truncate(outcome.reason(), 500));
        ins.setInspectedAt(now);
        ins.setUpdatedBy(operator);
        ins.setUpdatedAt(now);
        inspectionMapper.updateById(ins);

        QcBatch batch = batchMapper.selectById(ins.getBatchId());
        if (batch != null) {
            // 提交只是「等审核」，批次还不算做完。放行后由 review 置 DONE，
            // 否则一张还没人审过的单会让批次显示已完成，看板上的合格率就提前被算进去了
            batch.setStatus("INSPECTING");
            batch.setUpdatedAt(now);
            batchMapper.updateById(batch);
        }

        // 提交即锁定：日志里记下判定结论，这是后续追溯的第一条证据
        writeLog(inspectionId, "SUBMIT", from, "SUBMITTED", operator, null, outcome.reason());
        return outcome;
    }

    // ==================== 状态操作 ====================

    /** 解锁：已提交的单据要改，必须走这里，并留下理由 */
    @Transactional
    public void reopen(Long inspectionId, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of("解锁必须填写理由");
        }
        QcInspection ins = require(inspectionId);
        if (!"SUBMITTED".equals(ins.getStatus())) {
            throw BizException.of("只有已提交的检验单可以解锁，当前状态：" + ins.getStatus());
        }
        String from = ins.getStatus();
        ins.setStatus("DRAFT");
        ins.setResult("NA");
        ins.setPassedQty(0);
        ins.setFailedQty(0);
        ins.setJudgeReason("已解锁：" + reason);
        ins.setUpdatedBy(operator);
        ins.setUpdatedAt(LocalDateTime.now());
        inspectionMapper.updateById(ins);

        // 旧的审核痕迹要真的抹掉。MyBatis-Plus 的 updateById 默认跳过 null 字段，
        // 只把实体里的 reviewedBy 置 null 是写不进库的，必须用 UpdateWrapper 显式 set
        inspectionMapper.update(null, new LambdaUpdateWrapper<QcInspection>()
                .eq(QcInspection::getId, inspectionId)
                .set(QcInspection::getReviewedBy, null)
                .set(QcInspection::getReviewedAt, null)
                .set(QcInspection::getReviewComment, null));

        writeLog(inspectionId, "REOPEN", from, "DRAFT", operator, reason, "解锁原因：" + reason);
    }

    /** 归档 */
    @Transactional
    public void close(Long inspectionId, String operator) {
        QcInspection ins = require(inspectionId);
        if (!"SUBMITTED".equals(ins.getStatus())) {
            throw BizException.of("只有已提交的检验单可以归档，当前状态：" + ins.getStatus());
        }
        String from = ins.getStatus();
        ins.setStatus("CLOSED");
        ins.setUpdatedBy(operator);
        ins.setUpdatedAt(LocalDateTime.now());
        inspectionMapper.updateById(ins);
        writeLog(inspectionId, "CLOSE", from, "CLOSED", operator, null, "归档");
    }

    // ==================== 审核 ====================

    /**
     * 审核：通过则归档，退回则回到草稿由检验员修正。
     *
     * <p>这是「判定」与「放行」分离的那一刀。检验员提交时引擎已经判完了合格与否，
     * 审核员要做的不是重新算一遍，而是对这次判定的有效性背书 —— 抽样是否按标准、
     * 量具是否在有效期、NG 是否都挂了处置。所以退回必须写意见，否则检验员不知道改什么。
     *
     * <p>不允许审核自己录入的单据：自检自判是体系漏洞，管理员例外但要留痕说明。
     */
    @Transactional
    public void review(Long inspectionId, boolean approve, String comment, String operator) {
        QcInspection ins = require(inspectionId);
        if (!"SUBMITTED".equals(ins.getStatus())) {
            throw BizException.of("只有已提交待审核的检验单可以审核，当前状态：" + ins.getStatus());
        }
        if (!approve && (comment == null || comment.isBlank())) {
            throw BizException.of("退回必须填写审核意见，否则检验员不知道要改什么");
        }

        boolean selfReview = ins.getInspectorName() != null && ins.getInspectorName().equals(operator);
        if (selfReview && !UserContext.isAdmin()) {
            throw BizException.of("不能审核自己录入的检验单。判定与放行需要由不同的人完成");
        }

        String from = ins.getStatus();
        LocalDateTime now = LocalDateTime.now();
        String note = comment == null ? null : comment.trim();

        if (approve) {
            ins.setStatus("CLOSED");
            writeLog(inspectionId, "REVIEW", from, "CLOSED", operator, note,
                    "审核通过" + (selfReview ? "（管理员代审本人录入单据）" : "")
                            + (note == null || note.isBlank() ? "" : "：" + note));
        } else {
            ins.setStatus("DRAFT");
            ins.setResult("NA");
            ins.setPassedQty(0);
            ins.setFailedQty(0);
            ins.setNgCount(0);
            ins.setCriticalNgCount(0);
            ins.setJudgeReason("审核退回：" + note);
            // 这次判定被否了，它派生的不良记录也一并撤掉：
            // 留在处置队列里会被人当成真的待返工品去处理，重新提交时会按新数据重建
            defectMapper.delete(new LambdaQueryWrapper<QcDefect>()
                    .eq(QcDefect::getInspectionId, inspectionId));
            writeLog(inspectionId, "REJECT", from, "DRAFT", operator, note, "审核退回：" + note);

            // 退回是一次需要对方动作的事件：不主动落到录入人的通知里，
            // 单子就会一直躺在 DRAFT，而检验员在产线上永远不会知道
            noticeService.push(ins.getCreatedBy(), NoticeService.TYPE_REVIEW_REJECT,
                    "检验单被退回：" + ins.getInspectionNo(),
                    note,
                    "INSPECTION", inspectionId, ins.getInspectionNo());
        }

        ins.setReviewedBy(operator);
        ins.setReviewedAt(now);
        ins.setReviewComment(note);
        ins.setUpdatedBy(operator);
        ins.setUpdatedAt(now);
        inspectionMapper.updateById(ins);

        // 归档后批次才算真正放行；退回则批次回到检验中
        QcBatch batch = ins.getBatchId() == null ? null : batchMapper.selectById(ins.getBatchId());
        if (batch != null) {
            batch.setStatus(approve ? "DONE" : "INSPECTING");
            batch.setUpdatedAt(now);
            batchMapper.updateById(batch);
        }
    }

    /** 作废：录错了整单不要了走这条路，而不是删数据 */
    @Transactional
    public void voidInspection(Long inspectionId, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of("作废必须填写理由");
        }
        QcInspection ins = require(inspectionId);
        if ("CLOSED".equals(ins.getStatus()) || "VOID".equals(ins.getStatus())) {
            throw BizException.of("该检验单已" + ("CLOSED".equals(ins.getStatus()) ? "归档" : "作废") + "，不能再作废");
        }
        String from = ins.getStatus();
        ins.setStatus("VOID");
        ins.setResult("NA");
        ins.setPassedQty(0);
        ins.setFailedQty(0);
        ins.setJudgeReason("已作废：" + reason);
        ins.setUpdatedBy(operator);
        ins.setUpdatedAt(LocalDateTime.now());
        inspectionMapper.updateById(ins);
        writeLog(inspectionId, "VOID", from, "VOID", operator, reason, "作废原因：" + reason);
        releaseBatchIfEmpty(ins.getBatchId());
    }

    /**
     * 批次下已经没有有效检验单时，把批次退回待检。
     *
     * <p>没有这一步，作废一张单会让批次永远停在 {@code INSPECTING}：看板上显示「在检」，
     * 实际上一个有效结论都没有、也没人在检，而且按状态筛的待检列表永远捞不到它——
     * 这类批次会安静地烂在库里，直到出货时才被拦下来。
     *
     * <p>{@code DONE} 的批次不动：它背后有已归档的结论，改回待检会让历史失真。
     * 正常情况下也走不到这里——{@code CLOSED} 的单不允许作废。
     */
    private void releaseBatchIfEmpty(Long batchId) {
        if (batchId == null) {
            return;
        }
        QcBatch batch = batchMapper.selectById(batchId);
        if (batch == null || "DONE".equals(batch.getStatus())) {
            return;
        }
        long live = inspectionMapper.selectCount(new LambdaQueryWrapper<QcInspection>()
                .eq(QcInspection::getBatchId, batchId)
                .in(QcInspection::getStatus, "DRAFT", "SUBMITTED", "CLOSED"));
        if (live == 0 && !"PENDING".equals(batch.getStatus())) {
            batch.setStatus("PENDING");
            batch.setUpdatedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
        }
    }

    /** 操作日志 */
    public List<QcInspectionLog> logs(Long inspectionId) {
        return logMapper.selectList(new LambdaQueryWrapper<QcInspectionLog>()
                .eq(QcInspectionLog::getInspectionId, inspectionId)
                .orderByDesc(QcInspectionLog::getId));
    }

    // ==================== 查询 ====================

    /** 检验单详情：按件组织，每件内按分组归并，供前端切件 + 分组折叠 */
    public Map<String, Object> detail(Long inspectionId) {
        QcInspection ins = require(inspectionId);
        List<QcInspectionLine> lines = lineMapper.selectList(new LambdaQueryWrapper<QcInspectionLine>()
                .eq(QcInspectionLine::getInspectionId, inspectionId)
                .orderByAsc(QcInspectionLine::getPieceNo)
                .orderByAsc(QcInspectionLine::getSeq));

        Map<Integer, List<QcInspectionLine>> byPiece = new TreeMap<>();
        for (QcInspectionLine line : lines) {
            byPiece.computeIfAbsent(line.getPieceNo() == null ? 1 : line.getPieceNo(),
                    k -> new ArrayList<>()).add(line);
        }

        List<Map<String, Object>> pieces = new ArrayList<>(byPiece.size());
        for (Map.Entry<Integer, List<QcInspectionLine>> e : byPiece.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pieceNo", e.getKey());
            p.put("rows", e.getValue());
            p.put("groups", groupBy(e.getValue()));
            pieces.add(p);
        }

        int itemCount = lines.isEmpty() ? 0
                : (int) lines.stream().map(QcInspectionLine::getItemId).distinct().count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspection", ins);
        result.put("pieces", pieces);
        result.put("groups", groupBy(lines));
        result.put("lineCount", lines.size());
        result.put("itemCount", itemCount);
        result.put("pieceCount", byPiece.size());
        // 已锁定的单据前端只读
        result.put("locked", "SUBMITTED".equals(ins.getStatus()) || "CLOSED".equals(ins.getStatus()));
        // 复检单要能说出「我是谁的复检」：只给一个内部 id，报告上印出来没人看得懂
        if (ins.getRecheckOfId() != null) {
            QcInspection origin = inspectionMapper.selectById(ins.getRecheckOfId());
            if (origin != null) {
                result.put("recheckOfNo", origin.getInspectionNo());
            }
        }
        return result;
    }

    // ==================== 内部方法 ====================

    private QcInspection require(Long inspectionId) {
        QcInspection ins = inspectionMapper.selectById(inspectionId);
        if (ins == null) {
            throw BizException.of("检验单不存在：" + inspectionId);
        }
        return ins;
    }

    private void assertDraft(QcInspection ins) {
        String status = ins.getStatus();
        switch (status) {
            case "DRAFT" -> {
            }
            case "SUBMITTED" -> throw BizException.of(
                    "检验单已提交锁定，不能直接修改。如确需更正，请先申请解锁（reopen）并填写理由");
            case "CLOSED" -> throw BizException.of("检验单已归档，不能修改");
            case "VOID" -> throw BizException.of("检验单已作废，不能修改");
            default -> throw BizException.of("检验单状态异常：" + status);
        }
    }

    private Map<String, List<QcInspectionLine>> groupBy(List<QcInspectionLine> lines) {
        Map<String, List<QcInspectionLine>> groups = new LinkedHashMap<>();
        for (QcInspectionLine line : lines) {
            String g = line.getGroupName() == null ? "其他" : line.getGroupName();
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(line);
        }
        return groups;
    }

    private void writeLog(Long inspectionId, String action, String from, String to,
                          String operator, String reason, String summary) {
        QcInspectionLog log = new QcInspectionLog();
        log.setInspectionId(inspectionId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setReason(reason);
        log.setOperator(operator);
        log.setSummary(truncate(summary, 1000));
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
    }

    private ItemSpec toSpec(QcInspectionLine line) {
        ValueType type = ValueType.valueOf(line.getValueType());
        ValueMode mode = ValueMode.valueOf(line.getValueMode());
        List<EnumOption> options = null;
        if (type == ValueType.ENUM && line.getOptionsJson() != null) {
            try {
                options = objectMapper.readValue(line.getOptionsJson(), new TypeReference<List<EnumOption>>() {
                });
            } catch (Exception e) {
                throw BizException.of("选项配置解析失败：" + line.getItemName());
            }
        }
        return new ItemSpec(type, mode,
                line.getPointCount() == null ? 1 : line.getPointCount(),
                line.getMinValue(), line.getMaxValue(), options,
                Boolean.TRUE.equals(line.getBoolPassValue()));
    }

    private String nextNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long count = inspectionMapper.countByDayPrefix(day);
        return "QC" + day + String.format("%04d", count + 1);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw BizException.of("实测值序列化失败");
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
