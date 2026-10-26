package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcBatchMapper extends BaseMapper<QcBatch> {

    /**
     * 供应商来料质量排名。
     *
     * <p>IQC 真正要回答的问题不是「今天合格率多少」，而是「哪家厂的货在拖后腿」。
     * 所以这里按供应商聚合，并且<strong>把件级合格率最低的排在最前面</strong>——
     * 排序朝下才有用，好供应商不需要人管，坏供应商需要。
     *
     * <p>两个口径都给：{@code lots} 是检验单数（批次级），{@code piece_*} 是件级。
     * 批次数和检验单数分开统计，是因为一个批次可能返工重检，直接相减会得出负数。
     *
     * <p>没填供应商的批次归到「（未填供应商）」而不是丢弃：
     * 这行数字本身就是个待办，丢掉它等于把问题藏起来。
     */
    @Select("""
            SELECT coalesce(s.supplier_name,
                            nullif(btrim(b.supplier), ''),
                            '（未填供应商）')                                        AS supplier,
                   coalesce(max(s.supplier_code), '')                                AS supplier_code,
                   count(DISTINCT b.id)                                              AS batches,
                   count(i.id)                                                       AS lots,
                   coalesce(sum(CASE WHEN i.result = 'PASS' THEN 1 ELSE 0 END), 0)   AS lots_pass,
                   coalesce(sum(CASE WHEN i.result = 'FAIL' THEN 1 ELSE 0 END), 0)   AS lots_fail,
                   coalesce(sum(i.sample_qty), 0)                                    AS piece_total,
                   coalesce(sum(i.passed_qty), 0)                                    AS piece_pass,
                   count(DISTINCT CASE WHEN b.supplier_id IS NULL
                                        AND btrim(coalesce(b.supplier, '')) <> ''
                                       THEN b.id END)                                AS unlinked
            FROM qc_batch b
            LEFT JOIN qc_supplier s ON s.id = b.supplier_id AND s.del_flag = 0
            LEFT JOIN qc_inspection i
                   ON i.batch_id = b.id
                  AND i.del_flag = 0
                  AND i.status IN ('SUBMITTED', 'CLOSED')
            WHERE b.del_flag = 0
            GROUP BY 1
            ORDER BY CASE WHEN coalesce(sum(i.sample_qty), 0) = 0 THEN 1 ELSE 0 END,
                     coalesce(sum(i.passed_qty), 0)::numeric / NULLIF(sum(i.sample_qty), 0) ASC,
                     count(DISTINCT b.id) DESC
            """)
    List<Map<String, Object>> supplierQuality();

    /**
     * 月度供应商来料质量。口径与 {@link #supplierQuality()} 一致，只是把时间窗
     * 挪到这个月——年度对比时，全量排名会一直被去年的老账压着，看不出这个月谁在退步。
     *
     * <p>按件级合格率升序，最差的排前面。件数为 0 的供应商排在最后：
     * 这个月没来货的供应商不该出现在「本月最差」里。
     */
    @Select("""
            SELECT coalesce(s.supplier_name,
                            nullif(btrim(b.supplier), ''),
                            '（未填供应商）')                                        AS supplier,
                   count(DISTINCT b.id)                                            AS batches,
                   count(i.id)                                                     AS lots,
                   coalesce(sum(i.sample_qty), 0)                                  AS piece_total,
                   coalesce(sum(i.passed_qty), 0)                                  AS piece_pass,
                   coalesce(sum(CASE WHEN i.result = 'PASS' THEN 1 ELSE 0 END), 0) AS lots_pass
            FROM qc_batch b
            LEFT JOIN qc_supplier s ON s.id = b.supplier_id AND s.del_flag = 0
            JOIN qc_inspection i
              ON i.batch_id = b.id
             AND i.del_flag = 0
             AND i.status IN ('SUBMITTED', 'CLOSED')
             AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
             AND coalesce(i.inspected_at, i.created_at) <  CAST(#{to} AS timestamptz)
            WHERE b.del_flag = 0
            GROUP BY 1
            ORDER BY coalesce(sum(i.passed_qty), 0)::numeric / NULLIF(sum(i.sample_qty), 0) ASC NULLS LAST
            """)
    java.util.List<Map<String, Object>> monthlySupplier(@Param("from") String from,
                                                        @Param("to") String to);
}
