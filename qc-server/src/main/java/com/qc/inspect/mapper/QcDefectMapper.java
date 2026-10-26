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

    /**
     * 月度不良 Top N。
     *
     * <p>排序按不良<b>件数</b>（qty）而不是出现次数：一个项在 50 件里各坏 1 件，
     * 和在一个项上坏了 50 件，后者在车间里是更要紧的事。qty 相同才比次数。
     *
     * <p>按 defect_name 而不是 defect_code 分组：现场不良单里 code 常常是空的
     * （临时性缺陷没来得及建码），按 code 分会出现一整行 NULL 吃掉大半个帕累托。
     */
    @Select("""
            SELECT d.defect_name                                  AS name,
                   count(*)                                       AS cnt,
                   coalesce(sum(d.defect_qty), 0)                 AS qty
            FROM qc_defect d
            JOIN qc_inspection i ON i.id = d.inspection_id
            WHERE i.del_flag = 0
              AND i.status IN ('SUBMITTED', 'CLOSED')
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
              AND coalesce(i.inspected_at, i.created_at) <  CAST(#{to} AS timestamptz)
            GROUP BY d.defect_name
            ORDER BY qty DESC, cnt DESC
            """)
    List<Map<String, Object>> monthlyTop(@Param("from") String from, @Param("to") String to);

    /**
     * 月度不良处置分布。返工 / 报废 / 让步 / 退货各多少件。
     *
     * <p>这张表回答的是「不良品最后怎么处理了」，是管理评审里最容易被追问的一块：
     * 让步接收多，说明标准定得比工艺能力高；报废多，说明过程控制出了问题。
     */
    @Select("""
            SELECT d.disposition                                  AS disposition,
                   count(*)                                       AS cnt,
                   coalesce(sum(d.defect_qty), 0)                 AS qty
            FROM qc_defect d
            JOIN qc_inspection i ON i.id = d.inspection_id
            WHERE i.del_flag = 0
              AND i.status IN ('SUBMITTED', 'CLOSED')
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
              AND coalesce(i.inspected_at, i.created_at) <  CAST(#{to} AS timestamptz)
            GROUP BY d.disposition
            ORDER BY qty DESC
            """)
    List<Map<String, Object>> monthlyDisposition(@Param("from") String from, @Param("to") String to);
}
