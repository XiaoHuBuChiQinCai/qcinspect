package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcSupplier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcSupplierMapper extends BaseMapper<QcSupplier> {

    /**
     * 下一个可用编码。用「现有最大号 + 1」而不是 count + 1：
     * 供应商停用删除过之后，count 会往回退，生成的编码会和历史编码撞唯一索引。
     */
    @Select("SELECT coalesce(max(CAST(substring(supplier_code FROM 4) AS int)), 0) + 1"
            + " FROM qc_supplier WHERE supplier_code ~ '^SUP[0-9]+$'")
    long nextCodeSeq();

    /**
     * 供应商一览：带上引用批次数量与本月/累计件级合格率。
     *
     * <p>列表页要回答的不是「有哪些供应商」，而是「哪些供应商该找他们谈话」。
     * 所以在主数据列表上直接给合格率，省得为了看一个数字来回切两个页面。
     */
    @Select("""
            SELECT s.id,
                   s.supplier_code,
                   s.supplier_name,
                   s.short_name,
                   s.category,
                   s.contact,
                   s.phone,
                   s.level,
                   s.status,
                   s.remark,
                   coalesce(b.batches, 0)     AS batches,
                   coalesce(b.piece_total, 0) AS piece_total,
                   coalesce(b.piece_pass, 0)  AS piece_pass
            FROM qc_supplier s
            LEFT JOIN (
                SELECT bb.supplier_id,
                       count(DISTINCT bb.id)                                     AS batches,
                       coalesce(sum(i.sample_qty), 0)                            AS piece_total,
                       coalesce(sum(i.passed_qty), 0)                            AS piece_pass
                FROM qc_batch bb
                JOIN qc_inspection i
                  ON i.batch_id = bb.id
                 AND i.del_flag = 0
                 AND i.status IN ('SUBMITTED', 'CLOSED')
                WHERE bb.del_flag = 0 AND bb.supplier_id IS NOT NULL
                GROUP BY bb.supplier_id
            ) b ON b.supplier_id = s.id
            WHERE s.del_flag = 0
              AND (CAST(#{keyword} AS text) IS NULL
                   OR s.supplier_code ILIKE '%' || CAST(#{keyword} AS text) || '%'
                   OR s.supplier_name ILIKE '%' || CAST(#{keyword} AS text) || '%'
                   OR s.short_name    ILIKE '%' || CAST(#{keyword} AS text) || '%')
              AND (CAST(#{status} AS text) IS NULL OR s.status = CAST(#{status} AS text))
            ORDER BY CASE s.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                     s.supplier_code
            """)
    List<Map<String, Object>> overview(@Param("keyword") String keyword, @Param("status") String status);
}
