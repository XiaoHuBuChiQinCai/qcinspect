package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcGauge;
import com.qc.inspect.entity.QcGaugeCalibration;
import com.qc.inspect.mapper.QcGaugeCalibrationMapper;
import com.qc.inspect.mapper.QcGaugeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 全部量具，默认含已过期的（要让人看见问题，而不是藏起来） */
    public List<QcGauge> list(String status) {
        return gaugeMapper.selectList(new LambdaQueryWrapper<QcGauge>()
                .eq(status != null && !status.isBlank(), QcGauge::getStatus, status)
                .orderByAsc(QcGauge::getGaugeCode));
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
            g.setUpdatedAt(now);
            g.setUpdatedBy(operator);
            if ("FAIL".equals(r)) {
                // 有效期原样留着（多半已经过期或即将过期），量具停下等人修
                g.setStatus("SUSPENDED");
            } else {
                g.setCalibrateDate(calibrateDate);
                g.setCalibrateCycleDays(cycle);
                g.setNextCalibrateDate(next);
                // 之前因校准不合格停用的，修好后重新校准合格就恢复可用
                g.setStatus("ACTIVE");
            }
            gaugeMapper.updateById(g);
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
            throw BizException.of(prefix + "选用的量具 " + g.getGaugeCode()
                    + " 上次校准不合格，已停用。维修并重新校准合格后才能再用");
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
