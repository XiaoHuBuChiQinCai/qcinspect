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

    /**
     * 供应商画像的汇总：批次/单数/件数，外加「零不良几张、有不良几张、最差那批多少」。
     *
     * <p>后三个数是这张画像跟「合格率排名」的区别所在。12 批里 11 批零不良、1 批废掉 40%，
     * 和 12 批每批坏 5%，平均下来都写着 95%——前者要查那一批，后者要改工艺，
     * 该做的事一件都不沾边。
     *
     * <p>只算 {@code SUBMITTED / CLOSED}：草稿和作废单不代表任何结论，
     * 算进去会让合格率随「今天谁开了几张单没交」而波动。
     *
     * <p>{@code worst_batch_rate} 只在 {@code sample_qty > 0} 时算——
     * 件数为 0 的单除以 0 会变成 NULL 或报错，而不是「合格率 0」。
     */
    @Select("""
            SELECT count(DISTINCT bb.id)                                          AS batches,
                   count(*)                                                       AS lots,
                   coalesce(sum(i.sample_qty), 0)                                 AS piece_total,
                   coalesce(sum(i.passed_qty), 0)                                 AS piece_pass,
                   count(*) FILTER (WHERE coalesce(i.failed_qty, 0) = 0)          AS perfect_lots,
                   count(*) FILTER (WHERE coalesce(i.failed_qty, 0) > 0)          AS bad_lots,
                   min(CASE WHEN i.sample_qty > 0
                            THEN i.passed_qty::numeric / i.sample_qty END)        AS worst_batch_rate
            FROM qc_batch bb
            JOIN qc_inspection i
              ON i.batch_id = bb.id
             AND i.del_flag = 0
             AND i.status IN ('SUBMITTED', 'CLOSED')
            WHERE bb.del_flag = 0
              AND bb.supplier_id = CAST(#{supplierId} AS bigint)
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
            """)
    Map<String, Object> profileSummary(@Param("supplierId") Long supplierId,
                                       @Param("from") String from);

    /**
     * 按月走势。
     *
     * <p>{@code AT TIME ZONE 'Asia/Shanghai'} 不能省：{@code inspected_at} 是 timestamptz，
     * 直接 {@code date_trunc('month', ...)} 按数据库时区（容器默认 UTC）截断，
     * 月初最后 8 小时的检验会被算到上个月——月末那张单在趋势图上跳到上个月去了。
     */
    @Select("""
            SELECT to_char((coalesce(i.inspected_at, i.created_at)
                            AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM')       AS month,
                   count(DISTINCT bb.id)                                          AS batches,
                   count(*)                                                       AS lots,
                   coalesce(sum(i.sample_qty), 0)                                 AS piece_total,
                   coalesce(sum(i.passed_qty), 0)                                 AS piece_pass,
                   coalesce(sum(i.failed_qty), 0)                                 AS piece_fail
            FROM qc_batch bb
            JOIN qc_inspection i
              ON i.batch_id = bb.id
             AND i.del_flag = 0
             AND i.status IN ('SUBMITTED', 'CLOSED')
            WHERE bb.del_flag = 0
              AND bb.supplier_id = CAST(#{supplierId} AS bigint)
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
            GROUP BY 1
            ORDER BY 1
            """)
    List<Map<String, Object>> profileMonthly(@Param("supplierId") Long supplierId,
                                             @Param("from") String from);

    /**
     * 这家供应商的不良都坏在哪。
     *
     * <p>占比的分母是该供应商自己的不良件数，不是全厂——「你家的问题里外径占 56%」
     * 才有用，「占全厂 3%」说明不了任何事。
     *
     * <p>不良标识用 {@code coalesce(nullif(btrim(defect_code), ''), defect_name)}，
     * 与 CAPA 的再犯检测同口径：现场不少不良没建代码，只按 code 分组会把它们全并成一个 NULL；
     * 而自动带出的代码可能是空串而不是 NULL，只写 coalesce 会让它们也并成一堆。
     */
    @Select("""
            SELECT coalesce(nullif(btrim(d.defect_code), ''), d.defect_name) AS code,
                   max(d.defect_name)                                        AS name,
                   count(*)                                                  AS cnt,
                   coalesce(sum(d.defect_qty), 0)                            AS qty,
                   count(DISTINCT i.batch_id)                                AS batches
            FROM qc_defect d
            JOIN qc_inspection i
              ON i.id = d.inspection_id
             AND i.del_flag = 0
             AND i.status IN ('SUBMITTED', 'CLOSED')
            JOIN qc_batch bb ON bb.id = i.batch_id AND bb.del_flag = 0
            WHERE bb.supplier_id = CAST(#{supplierId} AS bigint)
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
            GROUP BY 1
            ORDER BY sum(d.defect_qty) DESC, count(*) DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> profileDefects(@Param("supplierId") Long supplierId,
                                             @Param("from") String from,
                                             @Param("limit") int limit);
}
