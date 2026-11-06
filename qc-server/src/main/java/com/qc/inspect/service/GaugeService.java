package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcGaugeCalibration;
import com.qc.inspect.entity.QcGaugeCheck;
import com.qc.inspect.mapper.QcGaugeCalibrationMapper;
import com.qc.inspect.mapper.QcGaugeCheckMapper;
import com.qc.inspect.mapper.QcGaugeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 量具台账与校准有效性。
 *
 * <p>这里的 {@link #assertUsable} 是整个模块的价值所在：卡尺校准过期，
 * 它测出的数据在 IATF 16949 / ISO 9001 体系上全部无效，事后无法补救。
 * 所以录入时提示，提交时硬拦。
 */
@Service
@RequiredArgsConstructor
public class GaugeService {

    private final QcGaugeMapper gaugeMapper;
    private final QcGaugeCalibrationMapper calibrationMapper;
    private final QcGaugeCheckMapper checkMapper;

    /** 偏差与允差保留 4 位，和 numeric(18,4) 对齐：库里的值和界面上算的必须是同一个 */
    private static final int AMOUNT_SCALE = 4;

    /** 全部量具，默认含已过期的（要让人看见问题，而不是藏起来） */
    public List<QcGauge> list(String status) {
        List<QcGauge> rows = gaugeMapper.selectList(new LambdaQueryWrapper<QcGauge>()
                .eq(status != null && !status.isBlank(), QcGauge::getStatus, status)
                .orderByAsc(QcGauge::getGaugeCode));
        fillLastCheckDate(rows);
        return rows;
    }

    /** 按 id 批量回填「最近一次点检」，一次查询搞定，别在循环里查库 */
    private void fillLastCheckDate(List<QcGauge> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (QcGauge g : rows) {
            ids.add(g.getId());
        }
        List<QcGaugeCheck> checks = checkMapper.selectList(new LambdaQueryWrapper<QcGaugeCheck>()
                .in(QcGaugeCheck::getGaugeId, ids)
                .orderByDesc(QcGaugeCheck::getCheckDate)
                .orderByDesc(QcGaugeCheck::getId));
        Map<Long, LocalDate> last = new HashMap<>();
        for (QcGaugeCheck c : checks) {
            last.putIfAbsent(c.getGaugeId(), c.getCheckDate());
        }
        for (QcGauge g : rows) {
            g.setLastCheckDate(last.get(g.getId()));
        }
    }

    /** 未来 N 天内到期（含已过期）的量具，用于首页提醒 */
    public List<QcGauge> expiring(int days) {
        LocalDate deadline = LocalDate.now().plusDays(days);
        return gaugeMapper.selectList(new LambdaQueryWrapper<QcGauge>()
                .eq(QcGauge::getStatus, "ACTIVE")
                .le(QcGauge::getNextCalibrateDate, deadline)
                .orderByAsc(QcGauge::getNextCalibrateDate));
    }

    @Transactional
    public QcGauge save(QcGauge req) {
        if (req.getGaugeCode() == null || req.getGaugeCode().isBlank()) {
            throw BizException.of("量具编号不能为空");
        }
        if (req.getGaugeName() == null || req.getGaugeName().isBlank()) {
            throw BizException.of("量具名称不能为空");
        }
        if (req.getStatus() == null) {
            req.setStatus("ACTIVE");
        }
        if (req.getCalibrateCycleDays() == null || req.getCalibrateCycleDays() <= 0) {
            req.setCalibrateCycleDays(365);
        }
        if (req.getCalibrateDate() == null) {
            throw BizException.of("必须填写上次校准日期，否则无法判断是否在有效期内");
        }
        req.setNextCalibrateDate(req.getCalibrateDate().plusDays(req.getCalibrateCycleDays()));

        LocalDateTime now = LocalDateTime.now();
        if (req.getId() == null) {
            QcGauge dup = gaugeMapper.selectOne(new LambdaQueryWrapper<QcGauge>()
                    .eq(QcGauge::getGaugeCode, req.getGaugeCode()));
            if (dup != null) {
                throw BizException.of("量具编号已存在：" + req.getGaugeCode());
            }
            req.setCreatedAt(now);
            req.setUpdatedAt(now);
            gaugeMapper.insert(req);
            // 建档时填的校准日期也要成一条履历，否则新量具在履历页上是空的，
            // 跟「从来没校准过」长得一模一样
            recordCalibration(req, "PASS", "建档时登记的初始校准记录");
        } else {
            QcGauge old = gaugeMapper.selectById(req.getId());
            req.setUpdatedAt(now);
            gaugeMapper.updateById(req);
            // 编辑页能直接改「上次校准」日期（多半是当初录错了）。
            // 主表改了履历却没跟上，两边就对不上账了，所以补一条留痕，注明不是原始记录
            if (old != null && !req.getCalibrateDate().equals(old.getCalibrateDate())) {
                recordCalibration(req, "PASS", "编辑量具时修改校准日期，非原始校准记录");
            }
        }
        return req;
    }

    /** 落一条履历；同一把量具同一天已有记录时跳过（改日期不该因为撞车而失败） */
    private void recordCalibration(QcGauge g, String result, String remark) {
        if (existsOn(g.getId(), g.getCalibrateDate())) {
            return;
        }
        QcGaugeCalibration cal = new QcGaugeCalibration();
        cal.setGaugeId(g.getId());
        cal.setCalibrateDate(g.getCalibrateDate());
        cal.setCycleDays(g.getCalibrateCycleDays());
        cal.setNextCalibrateDate(g.getNextCalibrateDate());
        cal.setResult(result);
        cal.setCalibrateBy(g.getUpdatedBy() == null ? g.getCreatedBy() : g.getUpdatedBy());
        cal.setRemark(remark);
        cal.setCreatedAt(LocalDateTime.now());
        calibrationMapper.insert(cal);
    }

    /**
     * 登记一次校准，并落一条履历。
     *
     * <p>四条规则都是踩过现场才会想到的：
     * <ol>
     *   <li><b>同一天不许重复登记</b>：手抖点两下、两个人同时录，都要挡住。
     *       否则履历会长出两条一模一样的记录，审核时说不清哪条是真的</li>
     *   <li><b>补录旧记录不动主表</b>：校准证书常常晚到一个月，事后补录是常态。
     *       如果补录把主表的有效期改回去，等于把一个还在有效期内的量具改回过期</li>
     *   <li><b>不合格不顺延有效期</b>：校准出不合格还顺延，第二天就有人拿它测活。
     *       此时量具置 SUSPENDED 停用，修好重新校准合格才恢复</li>
     *   <li><b>限用必须写清范围</b>：不写清楚，下一个人会拿它去测本来不该测的尺寸</li>
     * </ol>
     */
    @Transactional
    public QcGauge calibrate(Long id, LocalDate calibrateDate, Integer cycleDays, String result,
                             String certificateNo, String agency, String remark, String operator) {
        QcGauge g = gaugeMapper.selectById(id);
        if (g == null) {
            throw BizException.of("量具不存在：" + id);
        }
        if ("SCRAPPED".equals(g.getStatus())) {
            throw BizException.of("量具 " + g.getGaugeCode() + " 已报废，不能再登记校准");
        }
        if (calibrateDate == null) {
            throw BizException.of("必须填写校准日期");
        }
        if (calibrateDate.isAfter(LocalDate.now())) {
            throw BizException.of("校准日期不能是未来时间");
        }
        String r = normalizeResult(result);
        if ("LIMITED".equals(r) && (remark == null || remark.isBlank())) {
            throw BizException.of("「限用」必须在备注里写清限制范围，否则下次有人拿它测不该测的尺寸");
        }
        if (existsOn(id, calibrateDate)) {
            throw BizException.of("该量具 " + calibrateDate + " 已登记过校准，同一天不能重复登记");
        }

        int cycle = cycleDays != null && cycleDays > 0 ? cycleDays : g.getCalibrateCycleDays();
        LocalDate next = calibrateDate.plusDays(cycle);
        LocalDateTime now = LocalDateTime.now();

        QcGaugeCalibration cal = new QcGaugeCalibration();
        cal.setGaugeId(id);
        cal.setCalibrateDate(calibrateDate);
        cal.setCycleDays(cycle);
        cal.setNextCalibrateDate(next);
        cal.setResult(r);
        cal.setCertificateNo(trimToNull(certificateNo));
        cal.setAgency(trimToNull(agency));
        cal.setCalibrateBy(operator);
        cal.setRemark(trimToNull(remark));
        cal.setCreatedAt(now);
        calibrationMapper.insert(cal);

        // 只有「最新的一次」才动主表。补录去年那张证书，不该改变当前有效期
        LocalDate current = g.getCalibrateDate();
        boolean newest = current == null || !calibrateDate.isBefore(current);
        if (newest) {
            boolean fail = "FAIL".equals(r);
            // 停用要说清是哪种不合格。点检不合格也走这条停用，
            // 文案写死成「校准不合格」会让人白跑一趟计量院
            String reason = fail
                    ? truncate("校准不合格 " + calibrateDate
                               + (remark == null || remark.isBlank() ? "" : "：" + remark))
                    : null;

            LambdaUpdateWrapper<QcGauge> up = new LambdaUpdateWrapper<QcGauge>()
                    .eq(QcGauge::getId, id)
                    .set(QcGauge::getUpdatedAt, now)
                    .set(QcGauge::getUpdatedBy, operator)
                    // 复校合格必须把停用原因清掉，否则界面还在说这把「点检不合格」
                    .set(QcGauge::getSuspendReason, reason);
            if (fail) {
                // 有效期原样留着（多半已经过期或即将过期），量具停下等人修
                up.set(QcGauge::getStatus, "SUSPENDED");
            } else {
                up.set(QcGauge::getCalibrateDate, calibrateDate)
                        .set(QcGauge::getCalibrateCycleDays, cycle)
                        .set(QcGauge::getNextCalibrateDate, next)
                        .set(QcGauge::getStatus, "ACTIVE");
                g.setCalibrateDate(calibrateDate);
                g.setCalibrateCycleDays(cycle);
                g.setNextCalibrateDate(next);
            }
            gaugeMapper.update(null, up);

            // 返回体要和库里一致：前端拿它直接刷详情卡，不能还是旧状态
            g.setStatus(fail ? "SUSPENDED" : "ACTIVE");
            g.setSuspendReason(reason);
            g.setUpdatedAt(now);
            g.setUpdatedBy(operator);
        }
        return g;
    }

    /** 某把量具的校准履历，按校准日期倒序 */
    public List<QcGaugeCalibration> calibrations(Long gaugeId) {
        return calibrationMapper.selectList(new LambdaQueryWrapper<QcGaugeCalibration>()
                .eq(QcGaugeCalibration::getGaugeId, gaugeId)
                .orderByDesc(QcGaugeCalibration::getCalibrateDate)
                .orderByDesc(QcGaugeCalibration::getId));
    }

    private boolean existsOn(Long gaugeId, LocalDate date) {
        return calibrationMapper.selectCount(new LambdaQueryWrapper<QcGaugeCalibration>()
                .eq(QcGaugeCalibration::getGaugeId, gaugeId)
                .eq(QcGaugeCalibration::getCalibrateDate, date)) > 0;
    }

    // ==================== 点检（期间核查） ====================

    /** 某把量具的点检履历，按日期倒序 */
    public List<QcGaugeCheck> checks(Long gaugeId) {
        return checkMapper.selectList(new LambdaQueryWrapper<QcGaugeCheck>()
                .eq(QcGaugeCheck::getGaugeId, gaugeId)
                .orderByDesc(QcGaugeCheck::getCheckDate)
                .orderByDesc(QcGaugeCheck::getId));
    }

    /**
     * 登记一次点检：拿标准件量一遍，偏差在允差内就继续用。
     *
     * <p>几条规矩都是现场来的：
     * <ol>
     *   <li><b>必须有标准件编号</b>：没有标准件的「点检」只是又量了一遍，证明不了任何事</li>
     *   <li><b>等于允差算合格</b>：与判定引擎一致，边界一律闭区间。
     *       偏差与允差都按 4 位小数取整后再比，否则 {@code 10.01 - 10} 在 double 里
     *       是 0.010000000000000675，界面说超差、后端说合格</li>
     *   <li><b>不合格立即停用</b>：点检的意义就是发现漂移，发现了还让它继续干活等于没查</li>
     *   <li><b>停用期间不许再点检</b>：这把已经不能用了，再点检只是往履历里堆噪声，
     *       该做的是送检复校</li>
     * </ol>
     */
    @Transactional
    public CheckResult submitCheck(Long gaugeId, LocalDate checkDate, String standardNo,
                                   BigDecimal standardValue, BigDecimal measuredValue,
                                   BigDecimal tolerance, String remark, String operator) {
        QcGauge g = gaugeMapper.selectById(gaugeId);
        if (g == null) {
            throw BizException.of("量具不存在：" + gaugeId);
        }
        if ("SCRAPPED".equals(g.getStatus())) {
            throw BizException.of("量具 " + g.getGaugeCode() + " 已报废，不用再点检");
        }
        if ("SUSPENDED".equals(g.getStatus())) {
            throw BizException.of("量具 " + g.getGaugeCode() + " 已停用："
                    + (g.getSuspendReason() == null ? "原因未记录" : g.getSuspendReason())
                    + "。请先送检复校，合格恢复可用后再点检");
        }
        if (checkDate == null) {
            throw BizException.of("必须填写点检日期");
        }
        if (checkDate.isAfter(LocalDate.now())) {
            throw BizException.of("点检日期不能是未来时间");
        }
        if (standardNo == null || standardNo.isBlank()) {
            throw BizException.of("必须填写标准件编号——没有标准件的点检，证明不了任何事");
        }
        if (standardValue == null || measuredValue == null) {
            throw BizException.of("标准件标称值和实测值都要填");
        }
        if (tolerance == null) {
            throw BizException.of("必须填写允差，否则无从判断合格与否");
        }
        if (tolerance.signum() < 0) {
            throw BizException.of("允差不能是负数，按 ± 填绝对值");
        }

        BigDecimal std = standardValue.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal mea = measuredValue.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal tol = tolerance.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal dev = mea.subtract(std);
        // 闭区间：偏差正好等于允差算合格
        boolean pass = dev.abs().compareTo(tol) <= 0;

        if (existsCheckOn(gaugeId, checkDate)) {
            throw BizException.of("该量具 " + checkDate + " 已点检过，同一天不能重复登记");
        }

        LocalDateTime now = LocalDateTime.now();
        QcGaugeCheck c = new QcGaugeCheck();
        c.setGaugeId(gaugeId);
        c.setCheckDate(checkDate);
        c.setStandardNo(standardNo.trim());
        c.setStandardValue(std);
        c.setMeasuredValue(mea);
        c.setTolerance(tol);
        c.setDeviation(dev);
        c.setResult(pass ? "PASS" : "FAIL");
        c.setCheckedBy(operator);
        c.setRemark(trimToNull(remark));
        c.setCreatedAt(now);
        checkMapper.insert(c);

        if (!pass) {
            String reason = truncate("点检不合格 " + checkDate
                    + "（偏差 " + dev.toPlainString() + "，允差 ±" + tol.toPlainString() + "）");
            gaugeMapper.update(null, new LambdaUpdateWrapper<QcGauge>()
                    .eq(QcGauge::getId, gaugeId)
                    .set(QcGauge::getStatus, "SUSPENDED")
                    .set(QcGauge::getSuspendReason, reason)
                    .set(QcGauge::getUpdatedBy, operator)
                    .set(QcGauge::getUpdatedAt, now));
            g.setStatus("SUSPENDED");
            g.setSuspendReason(reason);
            g.setUpdatedAt(now);
            g.setUpdatedBy(operator);
        }
        g.setLastCheckDate(checkDate);
        return new CheckResult(c, g);
    }

    /** 点检结果：带上更新后的量具状态，前端不用再查一次就知道有没有被停用 */
    public record CheckResult(QcGaugeCheck check, QcGauge gauge) {
    }

    /**
     * 本月还没点检的量具。
     *
     * <p>只算 ACTIVE 的：停用和报废的已经不能用了，催着点检没有意义，
     * 反而会把「该送检」的人引去「再量一遍」。
     *
     * @param daysSince 距上次点检多少天；从没点检过为 {@link Integer#MAX_VALUE}，排最前
     */
    public record PendingCheck(QcGauge gauge, LocalDate lastCheckDate, String lastResult, int daysSince) {
    }

    public List<PendingCheck> pendingChecks() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        List<QcGauge> actives = gaugeMapper.selectList(new LambdaQueryWrapper<QcGauge>()
                .eq(QcGauge::getStatus, "ACTIVE")
                .orderByAsc(QcGauge::getGaugeCode));
        if (actives.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (QcGauge g : actives) {
            ids.add(g.getId());
        }
        Map<Long, QcGaugeCheck> last = new HashMap<>();
        for (QcGaugeCheck c : checkMapper.selectList(new LambdaQueryWrapper<QcGaugeCheck>()
                .in(QcGaugeCheck::getGaugeId, ids)
                .orderByDesc(QcGaugeCheck::getCheckDate)
                .orderByDesc(QcGaugeCheck::getId))) {
            last.putIfAbsent(c.getGaugeId(), c);
        }

        List<PendingCheck> out = new ArrayList<>();
        for (QcGauge g : actives) {
            QcGaugeCheck c = last.get(g.getId());
            // 本月已经点检过的不再催
            if (c != null && !c.getCheckDate().isBefore(monthStart)) {
                continue;
            }
            if (c == null) {
                out.add(new PendingCheck(g, null, null, Integer.MAX_VALUE));
            } else {
                int days = (int) ChronoUnit.DAYS.between(c.getCheckDate(), today);
                out.add(new PendingCheck(g, c.getCheckDate(), c.getResult(), days));
            }
        }
        // 隔得最久的排最前：这一页是拿来催活的，不是拿来看台账的
        out.sort(Comparator.comparingInt(PendingCheck::daysSince).reversed()
                .thenComparing(p -> p.gauge().getGaugeCode()));
        return out;
    }

    private boolean existsCheckOn(Long gaugeId, LocalDate date) {
        return checkMapper.selectCount(new LambdaQueryWrapper<QcGaugeCheck>()
                .eq(QcGaugeCheck::getGaugeId, gaugeId)
                .eq(QcGaugeCheck::getCheckDate, date)) > 0;
    }

    /** 停用原因列只有 200 字符，超了截断，别让一次插入因为备注太长而失败 */
    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 199) + "…";
    }

    private static String normalizeResult(String result) {
        if (result == null || result.isBlank()) {
            return "PASS";
        }
        String r = result.trim().toUpperCase();
        if (!r.equals("PASS") && !r.equals("FAIL") && !r.equals("LIMITED")) {
            throw BizException.of("校准结果只能是 PASS（合格）/ FAIL（不合格）/ LIMITED（限用）");
        }
        return r;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 校验量具可用于本次测量。
     *
     * @throws BizException 不存在、已报废、校准不合格停用、或已过校准期
     */
    public QcGauge assertUsable(Long gaugeId, String itemName) {
        QcGauge g = gaugeMapper.selectById(gaugeId);
        String prefix = itemName == null ? "" : "「" + itemName + "」";
        if (g == null) {
            throw BizException.of(prefix + "填写的量具不存在：" + gaugeId);
        }
        if ("SCRAPPED".equals(g.getStatus())) {
            throw BizException.of(prefix + "选用的量具 " + g.getGaugeCode() + " 已报废，不能用于检验");
        }
        if ("SUSPENDED".equals(g.getStatus())) {
            // 停用原因可能是校准不合格，也可能是点检不合格。两句话的下一步不一样：
            // 一个要送计量院，一个可能只是标准件对不上。所以照实说，别写死一句
            String why = g.getSuspendReason() == null || g.getSuspendReason().isBlank()
                    ? "上次校准不合格"
                    : g.getSuspendReason();
            throw BizException.of(prefix + "选用的量具 " + g.getGaugeCode()
                    + " 已停用：" + why + "。维修并重新校准合格后才能再用");
        }
        LocalDate next = g.getNextCalibrateDate();
        if (next == null) {
            throw BizException.of(prefix + "选用的量具 " + g.getGaugeCode() + " 没有校准记录，不能用于检验");
        }
        if (next.isBefore(LocalDate.now())) {
            throw BizException.of(prefix + "选用的量具 " + g.getGaugeCode()
                    + " 已于 " + next + " 到期，超期量具测出的数据不可采信。请先送检校准");
        }
        return g;
    }

    /** 报废：报废后不能再被任何检验单选用 */
    @Transactional
    public void scrap(Long id, String reason) {
        QcGauge g = gaugeMapper.selectById(id);
        if (g == null) {
            throw BizException.of("量具不存在：" + id);
        }
        g.setStatus("SCRAPPED");
        g.setRemark(reason == null ? g.getRemark() : reason);
        g.setUpdatedAt(LocalDateTime.now());
        gaugeMapper.updateById(g);
    }

    /** 按 ID 取量具，不存在返回 null。供草稿暂存这类不做严格校验的场景使用 */
    public QcGauge get(Long id) {
        return id == null ? null : gaugeMapper.selectById(id);
    }
}
