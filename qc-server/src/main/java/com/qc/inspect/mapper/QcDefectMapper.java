package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcDefect;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcDefectMapper extends BaseMapper<QcDefect> {

    /**
     * 不良帕累托：按不良代码聚合，含占比与累计占比。
     *
     * <p>只统计已提交/已关闭的单据——草稿里的不良还没过审，算进去会把帕累托带偏。
     */
    @Select("""
            WITH agg AS (
                SELECT d.defect_code                                   AS code,
                       coalesce(max(d.defect_name), d.defect_code)     AS name,
                       coalesce(sum(d.defect_qty), 0)                  AS qty,
                       count(*)                                        AS occ
                FROM qc_defect d
                JOIN qc_inspection i ON i.id = d.inspection_id
                WHERE i.del_flag = 0
                  AND i.status IN ('SUBMITTED', 'CLOSED')
                  AND (#{productId} IS NULL OR i.product_id = #{productId})
                  AND (#{from} IS NULL OR i.created_at >= #{from}::timestamptz)
                GROUP BY d.defect_code
            )
            SELECT code, name, qty, occ,
                   round(qty * 100.0 / nullif(sum(qty) OVER (), 0), 2) AS percent,
                   round(sum(qty) OVER (ORDER BY qty DESC ROWS UNBOUNDED PRECEDING)
                         * 100.0 / nullif(sum(qty) OVER (), 0), 2)     AS cumulative_percent
            FROM agg
            ORDER BY qty DESC
            """)
    List<Map<String, Object>> pareto(@Param("productId") Long productId, @Param("from") String from);

    /** 某张检验单下的不良，按件号排 */
    @Select("""
            SELECT *
            FROM qc_defect
            WHERE inspection_id = #{inspectionId}
            ORDER BY piece_no, id
            """)
    List<QcDefect> byInspection(@Param("inspectionId") Long inspectionId);
}
