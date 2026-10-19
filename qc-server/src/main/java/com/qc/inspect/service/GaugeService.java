package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcGauge;
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
        } else {
            req.setUpdatedAt(now);
            gaugeMapper.updateById(req);
        }
        return req;
    }

    /** 送检后登记新校准日期 */
    @Transactional
    public QcGauge calibrate(Long id, LocalDate calibrateDate, Integer cycleDays) {
        QcGauge g = gaugeMapper.selectById(id);
        if (g == null) {
            throw BizException.of("量具不存在：" + id);
        }
        if (calibrateDate == null) {
            throw BizException.of("必须填写校准日期");
        }
        if (calibrateDate.isAfter(LocalDate.now())) {
            throw BizException.of("校准日期不能是未来时间");
        }
        int cycle = cycleDays != null && cycleDays > 0 ? cycleDays : g.getCalibrateCycleDays();
        g.setCalibrateDate(calibrateDate);
        g.setCalibrateCycleDays(cycle);
        g.setNextCalibrateDate(calibrateDate.plusDays(cycle));
        g.setUpdatedAt(LocalDateTime.now());
        gaugeMapper.updateById(g);
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

    /**
     * 校验量具可用于本次测量。
     *
     * @throws BizException 已报废、已过校准期、或不存在
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
}
