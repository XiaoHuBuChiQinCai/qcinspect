package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import com.qc.inspect.mapper.QcStandardItemMapper;
import com.qc.inspect.mapper.QcStandardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检验标准版本管理。
 *
 * <p>核心规则：标准永不原地修改。改版 = 新增 version + 旧版置 OBSOLETE，
 * 这样历史检验单引用的版本依然可追溯。
 */
@Service
@RequiredArgsConstructor
public class StandardService {

    private final QcStandardMapper standardMapper;
    private final QcStandardItemMapper standardItemMapper;

    /**
     * 以指定标准为模板发布新版本，并自动生效。
     */
    @Transactional
    public QcStandard publishNewVersion(Long sourceId) {
        QcStandard src = standardMapper.selectById(sourceId);
        if (src == null) {
            throw BizException.of("标准不存在：" + sourceId);
        }

        List<QcStandard> siblings = standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, src.getProductId()));
        int maxVersion = siblings.stream()
                .mapToInt(s -> s.getVersion() == null ? 0 : s.getVersion())
                .max()
                .orElse(0);

        LocalDateTime now = LocalDateTime.now();

        // 先作废旧生效版本，满足「同产品仅一个 ACTIVE」的唯一索引
        for (QcStandard s : siblings) {
            if ("ACTIVE".equals(s.getStatus())) {
                s.setStatus("OBSOLETE");
                s.setUpdatedAt(now);
                standardMapper.updateById(s);
            }
        }

        QcStandard nw = new QcStandard();
        nw.setStandardCode(src.getStandardCode());
        nw.setProductId(src.getProductId());
        nw.setVersion(maxVersion + 1);
        nw.setStatus("ACTIVE");
        nw.setSamplingType(src.getSamplingType());
        nw.setSampleSize(src.getSampleSize());
        nw.setAllowedNgCount(src.getAllowedNgCount());
        nw.setEffectiveFrom(LocalDate.now());
        nw.setRemark("由 v" + src.getVersion() + " 复制发布");
        nw.setCreatedAt(now);
        standardMapper.insert(nw);

        List<QcStandardItem> items = standardItemMapper.selectList(
                new LambdaQueryWrapper<QcStandardItem>()
                        .eq(QcStandardItem::getStandardId, src.getId())
                        .orderByAsc(QcStandardItem::getSeq));
        for (QcStandardItem si : items) {
            QcStandardItem copy = new QcStandardItem();
            BeanUtils.copyProperties(si, copy);
            copy.setId(null);
            copy.setStandardId(nw.getId());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            standardItemMapper.insert(copy);
        }
        return nw;
    }

    /**
     * 启用一个草稿版本（用于首次建标准）。
     */
    @Transactional
    public void activate(Long standardId) {
        QcStandard target = standardMapper.selectById(standardId);
        if (target == null) {
            throw BizException.of("标准不存在：" + standardId);
        }
        if ("ACTIVE".equals(target.getStatus())) {
            return;
        }
        List<QcStandard> siblings = standardMapper.selectList(new LambdaQueryWrapper<QcStandard>()
                .eq(QcStandard::getProductId, target.getProductId())
                .eq(QcStandard::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now();
        for (QcStandard s : siblings) {
            s.setStatus("OBSOLETE");
            s.setUpdatedAt(now);
            standardMapper.updateById(s);
        }
        target.setStatus("ACTIVE");
        target.setEffectiveFrom(LocalDate.now());
        target.setUpdatedAt(now);
        standardMapper.updateById(target);
    }
}
