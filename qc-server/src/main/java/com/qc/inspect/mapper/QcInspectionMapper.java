package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcInspection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcInspectionMapper extends BaseMapper<QcInspection> {

    /**
     * 当天已生成的单号数量（含已逻辑删除的，避免单号复用）。
     *
     * <p>参数必须显式 CAST 成 text：PostgreSQL 的 CONCAT 接受任意类型，
     * 一个没类型的占位符会让它无法推断，直接报「无法确定参数 $1 的数据类型」。
     * 这个错会让建单接口 500，也就是一张单都开不出来。
     */
    @Select("SELECT count(*) FROM qc_inspection WHERE inspection_no LIKE CONCAT('QC', CAST(#{day} AS text), '%')")
    long countByDayPrefix(@Param("day") String day);

    /**
     * 合格率统计：按日聚合。
     *
     * <p>同时返回两个口径：单级（整单判 PASS 的占比）和件级（合格件数 / 受检件数）。
     * 抽检场景下件级更准，前端展示件级，单级保留用于核对历史数据。
     * 作废单不计入统计——它不代表任何检验结论。
     */
    @Select("""
            SELECT to_char(coalesce(inspected_at, created_at), 'YYYY-MM-DD') AS day,
                   count(*)                                              AS total,
                   sum(CASE WHEN result = 'PASS' THEN 1 ELSE 0 END)      AS pass_cnt,
                   sum(sample_qty)                                       AS piece_total,
                   sum(passed_qty)                                       AS piece_pass
            FROM qc_inspection
            WHERE del_flag = 0 AND status IN ('SUBMITTED', 'CLOSED')
              AND coalesce(inspected_at, created_at) >= now() - interval '30 day'
            GROUP BY 1
            ORDER BY 1
            """)
    List<Map<String, Object>> statDaily();

    /**
     * 被审核退回、还没重新提交的草稿。
     *
     * <p>判定依据是「当前是草稿」且「历史上出现过 REJECT」——被退回后又改完提交的单
     * 状态会变 SUBMITTED，自然从待办里消失，不用额外标记已读。
     */
    @Select("""
            SELECT i.*
            FROM qc_inspection i
            WHERE i.del_flag = 0
              AND i.status = 'DRAFT'
              AND i.created_by = #{operator}
              AND EXISTS (SELECT 1 FROM qc_inspection_log l
                          WHERE l.inspection_id = i.id AND l.action = 'REJECT')
            ORDER BY i.updated_at DESC NULLS LAST, i.id DESC
            """)
    List<QcInspection> rejectedDrafts(@Param("operator") String operator);

    @Select("""
            SELECT count(*)
            FROM qc_inspection i
            WHERE i.del_flag = 0
              AND i.status = 'DRAFT'
              AND i.created_by = #{operator}
              AND EXISTS (SELECT 1 FROM qc_inspection_log l
                          WHERE l.inspection_id = i.id AND l.action = 'REJECT')
            """)
    long countRejectedDrafts(@Param("operator") String operator);

    /**
     * 月度指标：按月 × 检验类型聚合。
     *
     * <p>时间窗由服务层传进来（带 +08:00 偏移的字符串），不用数据库的 now()——
     * 月报是「上个月」这种明确区间，靠 now() 算出来的区间会随执行时刻漂移，
     * 补跑一次就可能和前一次对不上。
     *
     * <p>统计口径与日合格率一致：只算 SUBMITTED / CLOSED，作废单不代表任何结论。
     * 时间取 coalesce(inspected_at, created_at)——历史单里 inspected_at 可能为空，
     * 直接筛 inspected_at 会把老数据整段漏掉，月报上表现为「上上个月突然没数据」。
     */
    @Select("""
            SELECT inspect_type,
                   count(*)                                              AS orders,
                   sum(CASE WHEN result = 'PASS' THEN 1 ELSE 0 END)      AS pass_orders,
                   coalesce(sum(sample_qty), 0)                          AS piece_total,
                   coalesce(sum(passed_qty), 0)                          AS piece_pass,
                   coalesce(sum(failed_qty), 0)                          AS piece_fail,
                   sum(CASE WHEN recheck_of_id IS NOT NULL THEN 1 ELSE 0 END) AS recheck_orders
            FROM qc_inspection
            WHERE del_flag = 0
              AND status IN ('SUBMITTED', 'CLOSED')
              AND coalesce(inspected_at, created_at) >= CAST(#{from} AS timestamptz)
              AND coalesce(inspected_at, created_at) <  CAST(#{to} AS timestamptz)
            GROUP BY inspect_type
            ORDER BY inspect_type
            """)
    List<Map<String, Object>> monthlyByType(@Param("from") String from, @Param("to") String to);

    /**
     * 按检验员聚合。用于「这些记录是谁录的、录得可不可信」。
     *
     * <p>为什么单独加录入时长两列：<b>判得太快是数据质量的最强信号</b>。
     * 一张 40 行的单 90 秒提交完，物理上不可能真测过。反过来「慢」不说明问题——
     * 现场可能是开单与录入分开做（早上开单、下班前补录），时长天然很长。
     * 所以 {@code sec_total} 只用来算「每行几秒」并只对过快告警，
     * 不参与任何绩效结论。
     *
     * <p>{@code greatest(..., 0)} 兜底：服务器时钟回拨或脏数据导致
     * {@code inspected_at < created_at} 时，EXTRACT 会返回负数，
     * 汇总后能把整组平均时长拉成负数，页面显示「-3 分钟一行」。
     *
     * <p>别名全部写下划线：MyBatis 返回 Map 时驼峰转换不生效，键就是原始列标签。
     */
    @Select("""
            WITH ln AS (
                SELECT inspection_id, count(*) AS row_cnt
                FROM qc_inspection_line
                GROUP BY inspection_id
            )
            SELECT coalesce(nullif(btrim(i.inspector_name), ''), '（未填检验员）')            AS inspector,
                   count(*)                                                                 AS orders,
                   coalesce(sum(i.sample_qty), 0)                                           AS piece_total,
                   coalesce(sum(i.passed_qty), 0)                                           AS piece_pass,
                   coalesce(sum(i.failed_qty), 0)                                           AS piece_fail,
                   coalesce(sum(i.ng_count), 0)                                             AS ng_count,
                   coalesce(sum(i.critical_ng_count), 0)                                    AS critical_ng,
                   coalesce(sum(CASE WHEN i.result = 'PASS' THEN 1 ELSE 0 END), 0)           AS pass_orders,
                   coalesce(sum(CASE WHEN i.recheck_of_id IS NOT NULL THEN 1 ELSE 0 END), 0) AS recheck_orders,
                   coalesce(sum(coalesce(ln.row_cnt, 0)), 0)                                AS row_total,
                   coalesce(sum(EXTRACT(EPOCH FROM greatest(
                       coalesce(i.inspected_at, i.created_at) - i.created_at, interval '0'))), 0)
                                                                                            AS sec_total
            FROM qc_inspection i
            LEFT JOIN ln ON ln.inspection_id = i.id
            WHERE i.del_flag = 0
              AND i.status IN ('SUBMITTED', 'CLOSED')
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
              AND coalesce(i.inspected_at, i.created_at) <  CAST(#{to} AS timestamptz)
            GROUP BY 1
            ORDER BY count(*) DESC, 1
            """)
    List<Map<String, Object>> inspectorStats(@Param("from") String from, @Param("to") String to);

    /**
     * 缺陷分布（帕累托数据源）。
     */
    @Select("""
            SELECT defect_name, count(*) AS cnt, sum(defect_qty) AS qty
            FROM qc_defect
            GROUP BY defect_name
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> statDefect();

    /**
     * 已经被作废、但还挂着判定单的标准版本。
     *
     * <p>改版不是「改完就没事了」：旧版判过的单还躺在库里，它们的结论是按旧口径下的。
     * 质量管理要复查「哪些结论建立在一个已经不作数的标准上」，此前只能一张张点开看。
     *
     * <p>三个口径：
     * <ul>
     *   <li>只算 {@code SUBMITTED}/{@code CLOSED}：草稿还没定论，不该算「按旧版判的」</li>
     *   <li>只算 {@code standard_id} 对得上的：早期数据只存了版本号没存 id，
     *       关联不上就不硬凑——那部分单独由 {@link #countWithoutStandard} 报出总数，
     *       免得让人以为「旧版单就这么多」</li>
     *   <li>{@code HAVING count(i.id) > 0}：从没开过单的作废版本不占版面</li>
     * </ul>
     *
     * <p>LEFT JOIN 当前生效版本取 {@code current_version}：同产品只有一个 ACTIVE
     * （唯一索引 {@code uk_standard_active} 保证），不会把单据行数放大。
     */
    @Select("""
            <script>
            SELECT s.product_id,
                   p.product_code,
                   p.product_name,
                   s.id            AS standard_id,
                   s.standard_code,
                   s.version,
                   s.status,
                   to_char(s.effective_from, 'YYYY-MM-DD') AS effective_from,
                   cur.version     AS current_version,
                   count(i.id)     AS inspection_count,
                   count(i.id) FILTER (WHERE i.result = 'PASS') AS pass_count,
                   count(i.id) FILTER (WHERE i.result = 'FAIL') AS fail_count,
                   to_char(max(coalesce(i.inspected_at, i.created_at)) AT TIME ZONE 'Asia/Shanghai',
                           'YYYY-MM-DD') AS last_used_date
            FROM qc_standard s
            JOIN qc_product p ON p.id = s.product_id
            LEFT JOIN qc_standard cur
                   ON cur.product_id = s.product_id AND cur.status = 'ACTIVE' AND cur.del_flag = 0
            LEFT JOIN qc_inspection i
                   ON i.standard_id = s.id
                  AND i.del_flag = 0
                  AND i.status IN ('SUBMITTED', 'CLOSED')
            WHERE s.del_flag = 0
              AND s.status &lt;&gt; 'ACTIVE'
              <if test="productId != null">AND s.product_id = #{productId}</if>
            GROUP BY s.id, s.product_id, p.product_code, p.product_name,
                     s.standard_code, s.version, s.status, s.effective_from, cur.version
            HAVING count(i.id) &gt; 0
            ORDER BY count(i.id) DESC, s.product_id, s.version DESC
            </script>
            """)
    List<Map<String, Object>> staleStandardUsage(@Param("productId") Long productId);

    /**
     * 早期数据只记了版本号、没记 standardId 的已判定单数。
     *
     * <p>单独报这个数是为了把话说全：旧版标准下有多少单能查出来，
     * 还有多少单压根关联不上——只说前者会让人以为「就这些了」。
     */
    @Select("""
            SELECT count(*)
            FROM qc_inspection
            WHERE del_flag = 0
              AND status IN ('SUBMITTED', 'CLOSED')
              AND standard_id IS NULL
            """)
    long countWithoutStandard();
}
