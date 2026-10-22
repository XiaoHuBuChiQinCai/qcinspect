package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcBatch;
import org.apache.ibatis.annotations.Mapper;
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
            SELECT coalesce(nullif(btrim(b.supplier), ''), '（未填供应商）')          AS supplier,
                   count(DISTINCT b.id)                                              AS batches,
                   count(i.id)                                                       AS lots,
                   coalesce(sum(CASE WHEN i.result = 'PASS' THEN 1 ELSE 0 END), 0)   AS lots_pass,
                   coalesce(sum(CASE WHEN i.result = 'FAIL' THEN 1 ELSE 0 END), 0)   AS lots_fail,
                   coalesce(sum(i.sample_qty), 0)                                    AS piece_total,
                   coalesce(sum(i.passed_qty), 0)                                    AS piece_pass
            FROM qc_batch b
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
}
