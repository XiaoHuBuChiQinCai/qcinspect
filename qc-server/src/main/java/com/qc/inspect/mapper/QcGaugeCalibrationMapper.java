package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcGaugeCalibration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface QcGaugeCalibrationMapper extends BaseMapper<QcGaugeCalibration> {

    /**
     * 本月送校次数，按校准结论分档。
     *
     * <p>看的是 {@code calibrate_date}（实际送校日）而不是录入时间：
     * 补录三个月前的证书算那个月的工作量，不算这个月的。
     */
    @Select("""
            SELECT count(*)                                                          AS total,
                   coalesce(sum(CASE WHEN result = 'PASS' THEN 1 ELSE 0 END), 0)     AS pass_cnt,
                   coalesce(sum(CASE WHEN result = 'FAIL' THEN 1 ELSE 0 END), 0)     AS fail_cnt,
                   coalesce(sum(CASE WHEN result = 'LIMITED' THEN 1 ELSE 0 END), 0)  AS limited_cnt
            FROM qc_gauge_calibration
            WHERE del_flag = 0
              AND calibrate_date >= CAST(#{from} AS date)
              AND calibrate_date <  CAST(#{to} AS date)
            """)
    Map<String, Object> countInPeriod(@Param("from") String from, @Param("to") String to);
}
