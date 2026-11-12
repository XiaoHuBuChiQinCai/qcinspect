package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcCapa;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcCapaMapper extends BaseMapper<QcCapa> {

    /**
     * 当天已开的措施数量，作为单号种子。
     *
     * <p>占位符必须显式 CAST 成 text：PostgreSQL 的 CONCAT 接受任意类型，
     * 没类型的占位符它推断不出来，直接报「无法确定参数的数据类型」，接口 500。
     */
    @Select("SELECT count(*) FROM qc_capa WHERE capa_no LIKE CONCAT('CAPA', CAST(#{day} AS text), '%')")
    long countByDayPrefix(@Param("day") String day);

    /**
     * 同一产品、同一不良，在区间内出现了几次。
     *
     * <p>这是「这个毛病是不是老毛病」的数据来源。分成两个数看：
     * {@code occ} 是判出来的次数，{@code batches} 是涉及的批次数——同一批里出现五次
     * 和五批各出现一次，是完全不同的两件事，前者是这一批的问题，后者是过程的病。
     *
     * <p>匹配用 {@code coalesce(nullif(btrim(defect_code), ''), defect_name)}：现场不少不良
     * 没建代码，按 code 匹配会把这些全漏掉，而漏掉的恰好是临时性缺陷——它们才最容易反复。
     * 必须带 {@code nullif(btrim())}：从检验项自动带出的代码可能是空串而不是 NULL，
     * 只写 {@code coalesce} 会拿空串去比，永远匹配不上，而 Java 侧建单时用的是
     * {@code blankToNull()}（空串当没填）。两边口径一旦不一样，「再犯」就再也检测不到自己。
     */
    @Select("""
            SELECT count(*)                                    AS occ,
                   coalesce(sum(d.defect_qty), 0)              AS qty,
                   count(DISTINCT i.batch_id)                  AS batches,
                   max(coalesce(i.inspected_at, i.created_at)) AS last_at
            FROM qc_defect d
            JOIN qc_inspection i ON i.id = d.inspection_id
            WHERE i.del_flag = 0
              AND i.status IN ('SUBMITTED', 'CLOSED')
              AND i.product_id = CAST(#{productId} AS bigint)
              AND coalesce(nullif(btrim(d.defect_code), ''), d.defect_name) = CAST(#{defectKey} AS text)
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
            """)
    Map<String, Object> defectHistory(@Param("productId") Long productId,
                                      @Param("defectKey") String defectKey,
                                      @Param("from") String from);

    /**
     * 近窗口内所有不良簇，按产品 + 不良标识聚合。
     *
     * <p>这是「该立措施却没有立」的扫描入口。<b>批次数</b>是判断的关键：
     * 同一批里出现十次和三个批次各出现一次，前者是那批货的事，后者才是过程的病。
     * 所以这里同时给出 {@code occ} 与 {@code batches}，判断留给 {@code CapaRules.advice}。
     *
     * <p>不良标识与 {@link #defectHistory}、以及 Java 侧建单时的取舍完全同一口径，
     * 否则扫描扫出来的 key 和措施上存的 key 对不上，会出现「系统说它没立措施、
     * 其实早就立过一条」。
     */
    @Select("""
            WITH agg AS (
                SELECT i.product_id                                              AS product_id,
                       coalesce(nullif(btrim(d.defect_code), ''), d.defect_name) AS defect_key,
                       count(*)                                                  AS occ,
                       count(DISTINCT i.batch_id)                                AS batches,
                       coalesce(sum(d.defect_qty), 0)                            AS qty,
                       max(coalesce(i.inspected_at, i.created_at))               AS last_at
                FROM qc_defect d
                JOIN qc_inspection i ON i.id = d.inspection_id
                WHERE i.del_flag = 0
                  AND i.status IN ('SUBMITTED', 'CLOSED')
                  AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
                  AND coalesce(nullif(btrim(d.defect_code), ''), d.defect_name) IS NOT NULL
                GROUP BY i.product_id,
                         coalesce(nullif(btrim(d.defect_code), ''), d.defect_name)
            )
            SELECT a.product_id            AS product_id,
                   a.defect_key            AS defect_key,
                   a.occ                   AS occ,
                   a.batches               AS batches,
                   a.qty                   AS qty,
                   a.last_at               AS last_at,
                   to_char(a.last_at, 'YYYY-MM-DD') AS last_day,
                   p.product_code          AS product_code,
                   p.product_name          AS product_name
            FROM agg a
            LEFT JOIN qc_product p ON p.id = a.product_id AND p.del_flag = 0
            ORDER BY a.batches DESC, a.qty DESC, a.occ DESC
            """)
    List<Map<String, Object>> defectClusters(@Param("from") String from);

    /**
     * 所有措施的产品 + 不良标识 + 状态，用来和不良簇做匹配。
     *
     * <p>只取这三列：判断只需要「有没有还开着的」「有没有判过有效的」。
     * 数据量小，取全量在内存里分组，比在 SQL 里写一堆相关子查询清楚得多。
     */
    @Select("""
            SELECT product_id, defect_code, status, close_effective
            FROM qc_capa
            WHERE del_flag = 0
              AND product_id IS NOT NULL
              AND defect_code IS NOT NULL
            """)
    List<Map<String, Object>> capaFacts();

    /**
     * 按责任人统计还开着的措施数量。
     *
     * <p>用来回答「这个人手上压了几条」。压了十条和压了一条，跟踪的力度不该一样。
     */
    @Select("""
            SELECT c.owner                       AS owner,
                   count(*)                      AS open_cnt,
                   count(*) FILTER (WHERE c.due_date < CURRENT_DATE) AS overdue_cnt
            FROM qc_capa c
            WHERE c.del_flag = 0
              AND c.status IN ('OPEN', 'DOING', 'VERIFYING')
            GROUP BY c.owner
            ORDER BY count(*) DESC
            """)
    List<Map<String, Object>> openByOwner();
}
