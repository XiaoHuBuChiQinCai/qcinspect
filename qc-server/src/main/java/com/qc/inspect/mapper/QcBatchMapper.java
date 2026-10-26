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

    /**
     * 待检批次：已经建了批次，却<strong>一条有效检验单都没有</strong>。
     *
     * <p>判据是 {@code NOT EXISTS} 有效单，而不是 {@code status = 'PENDING'}。
     * 两者不等价：检验单被作废之后，批次状态还停在 {@code INSPECTING}，
     * 按状态筛会把这种最该被发现的批次漏掉——它看着像「在检」，
     * 实际上一张有效单都不剩。{@code DRAFT} 算有效：草稿意味着有人已经在动手，
     * 这不是漏检，只是没检完。
     *
     * <p>停留天数从 {@code coalesce(arrived_at, created_at)} 算：
     * 到货时间才是「这批料在厂里躺了多久」的起点。补录的历史批次如果 {@code arrived_at}
     * 填了三个月前，它就该立刻被判超期——那正是没人管的批次。
     *
     * <p>{@code greatest(..., 0)} 兜住未来时间：{@code arrived_at} 被误填成下个月时，
     * 负数天数会在排序里把这条沉到最底，等于把错误藏起来。
     *
     * <p>出货量只算 {@code NORMAL}，红冲的不算——作废的单没真发货，不能当「已出货」的证据。
     */
    @Select("""
            SELECT b.id,
                   b.batch_no,
                   b.inspect_type,
                   b.qty,
                   b.status,
                   b.supplier,
                   b.material_batch_no,
                   b.arrived_at,
                   b.created_at,
                   to_char(b.arrived_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS arrived_date,
                   to_char(b.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS created_date,
                   p.product_code,
                   p.product_name,
                   greatest(0, floor(extract(epoch from (now() - coalesce(b.arrived_at, b.created_at)))
                                     / 86400))::int                        AS idle_days,
                   (SELECT count(*) FROM qc_inspection i
                     WHERE i.batch_id = b.id AND i.del_flag = 0
                       AND i.status = 'VOID')                              AS void_lots,
                   (SELECT count(*) FROM qc_shipment s
                     WHERE s.batch_id = b.id AND s.del_flag = 0
                       AND s.status = 'NORMAL')                            AS shipments,
                   (SELECT coalesce(sum(s.qty), 0) FROM qc_shipment s
                     WHERE s.batch_id = b.id AND s.del_flag = 0
                       AND s.status = 'NORMAL')                            AS shipped_qty
            FROM qc_batch b
            JOIN qc_product p ON p.id = b.product_id
            WHERE b.del_flag = 0
              AND b.status IN ('PENDING', 'INSPECTING')
              AND NOT EXISTS (SELECT 1 FROM qc_inspection i
                               WHERE i.batch_id = b.id AND i.del_flag = 0
                                 AND i.status IN ('DRAFT', 'SUBMITTED', 'CLOSED'))
            ORDER BY coalesce(b.arrived_at, b.created_at) ASC, b.id ASC
            """)
    List<Map<String, Object>> pendingInspection();

    /**
     * 批次检验进度：一批一批地算「抽了几件、判了几件」。
     *
     * <p>按 id 批量查而不是在批次列表 SQL 里 join：列表那边是 MyBatis-Plus 的
     * 动态条件拼出来的（状态、产品、关键字四个条件按需组合），把聚合塞进去要
     * 手写一遍分页 + 条件，条件漏一个就是「筛出来的行数对、进度全错」——
     * 这种错最难看懂。按 id 回填只有一条 SQL，条件不可能漂移。
     *
     * <p>作废单（{@code VOID}）必须排除：作废意味着这张单的成绩不算数，
     * 留着它会让「已检 60 件」永远比实际多。
     *
     * <p>草稿单（{@code DRAFT}）必须算进去：抽检件数是建单时定的，有人建了单
     * 就说明这批已经在检了。它贡献 {@code sampled_qty} 但不贡献 {@code done_qty}
     * （件数是提交判定后才回写的），两者之差正好是「抽了还没判」。
     */
    @Select("""
            <script>
            SELECT i.batch_id                                   AS batch_id,
                   count(*)                                     AS lot_count,
                   coalesce(sum(i.sample_qty), 0)               AS sampled_qty,
                   coalesce(sum(i.passed_qty + i.failed_qty), 0) AS done_qty,
                   coalesce(sum(i.passed_qty), 0)               AS passed_qty,
                   coalesce(sum(i.failed_qty), 0)               AS failed_qty
            FROM qc_inspection i
            WHERE i.del_flag = 0
              AND i.status != 'VOID'
              AND i.batch_id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY i.batch_id
            </script>
            """)
    List<Map<String, Object>> progressByBatchIds(@Param("ids") List<Long> ids);
}
