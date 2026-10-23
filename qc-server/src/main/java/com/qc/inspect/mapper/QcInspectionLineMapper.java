package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcInspectionLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcInspectionLineMapper extends BaseMapper<QcInspectionLine> {

    /**
     * 可做过程能力分析的「产品 + 检验项」组合。
     *
     * <p>为什么按产品分组而不是只按检验项：同一个检验项（比如「外径」）会用在多个产品上，
     * 各产品的规格上下限不同。混在一起算 Cp/Cpk 等于拿两把尺子量同一批数，
     * 算出来的数没有任何意义。所以维度必须是「产品 + 检验项」。
     *
     * <p>只统计 NUMBER 类型：ENUM/BOOL/TEXT 没有均值和标准差，谈不上过程能力。
     * 只统计 SUBMITTED / CLOSED：草稿还在改，作废单不代表任何检验结论。
     *
     * <p>明细表自身没有 product_id，得从检验单上取。
     */
    @Select("""
            SELECT i.product_id,
                   max(p.product_name)                          AS product_name,
                   l.item_id,
                   max(l.item_code)                             AS item_code,
                   max(l.item_name)                             AS item_name,
                   max(l.unit)                                  AS unit,
                   count(DISTINCT i.id)                         AS inspection_cnt,
                   sum(jsonb_array_length(l.measured_values))   AS sample_cnt,
                   max(coalesce(i.inspected_at, i.created_at))  AS last_at
            FROM qc_inspection_line l
            JOIN qc_inspection i ON i.id = l.inspection_id
            LEFT JOIN qc_product p ON p.id = i.product_id
            WHERE l.value_type = 'NUMBER'
              AND l.measured_values IS NOT NULL
              AND jsonb_array_length(l.measured_values) > 0
              AND i.del_flag = 0
              AND i.status IN ('SUBMITTED', 'CLOSED')
            GROUP BY i.product_id, l.item_id
            HAVING sum(jsonb_array_length(l.measured_values)) >= 2
            ORDER BY product_name NULLS LAST, item_code
            """)
    List<Map<String, Object>> capabilityItems();

    /**
     * 过程能力取数：某产品某检验项在区间内的全部数值型明细。
     *
     * <p><b>超规值必须一起取出来。</b>把超规值剔掉再算 Cpk，等于自己给自己发合格证——
     * 那正是过程能力分析要抓的东西。
     *
     * <p>{@code measured_values::text}：jsonb 直接映射到 Map 会拿到 PGobject，
     * 显式转 text 才能当字符串解析。这个坑和 timestamptz 是同一类。
     *
     * <p>按 (检验单, 件号, 行) 排序：移动极差要在同一张单内部的相邻测量之间算，
     * 跨单相减得到的是「两批之间的漂移」，把它混进 σ̂ 会把组内变异算大、Cpk 算小。
     */
    @Select("""
            SELECT l.id,
                   l.inspection_id,
                   i.inspection_no,
                   i.product_id,
                   p.product_name,
                   l.piece_no,
                   l.item_code,
                   l.item_name,
                   l.unit,
                   l.min_value,
                   l.max_value,
                   l.measured_values::text AS measured_values
            FROM qc_inspection_line l
            JOIN qc_inspection i ON i.id = l.inspection_id
            LEFT JOIN qc_product p ON p.id = i.product_id
            WHERE l.value_type = 'NUMBER'
              AND l.measured_values IS NOT NULL
              AND jsonb_array_length(l.measured_values) > 0
              AND i.del_flag = 0
              AND i.status IN ('SUBMITTED', 'CLOSED')
              AND i.product_id = #{productId}
              AND l.item_id = #{itemId}
              AND coalesce(i.inspected_at, i.created_at) >= #{from}
              AND coalesce(i.inspected_at, i.created_at) < #{to}
            ORDER BY i.id, l.piece_no, l.id
            """)
    List<Map<String, Object>> capabilitySamples(@Param("productId") Long productId,
                                                @Param("itemId") Long itemId,
                                                @Param("from") java.time.LocalDateTime from,
                                                @Param("to") java.time.LocalDateTime to);
}
