package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcGauge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface QcGaugeMapper extends BaseMapper<QcGauge> {

    /**
     * 量具状态快照。月报里量具这一块是<b>当前状态</b>而不是本月状态——
     * 「现在有几把量具超期没校」是随时要回答的问题，不需要等月底汇总。
     *
     * <p>报废的不算超期：已经停用退出计量管理了，再列进超期清单只会制造噪音。
     */
    @Select("""
            SELECT count(*)                                                          AS total,
                   coalesce(sum(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0)    AS active,
                   coalesce(sum(CASE WHEN status = 'SUSPENDED' THEN 1 ELSE 0 END), 0) AS suspended,
                   coalesce(sum(CASE WHEN status = 'SCRAPPED' THEN 1 ELSE 0 END), 0)  AS scrapped,
                   coalesce(sum(CASE WHEN status <> 'SCRAPPED'
                                      AND next_calibrate_date < CURRENT_DATE
                                     THEN 1 ELSE 0 END), 0)                      AS overdue
            FROM qc_gauge
            WHERE del_flag = 0
            """)
    Map<String, Object> snapshot();
}
