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
     * <p>匹配用 {@code coalesce(defect_code, defect_name)}：现场不少不良没建代码，
     * 按 code 匹配会把这些全漏掉，而漏掉的恰好是临时性缺陷——它们才最容易反复。
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
              AND coalesce(d.defect_code, d.defect_name) = CAST(#{defectKey} AS text)
              AND coalesce(i.inspected_at, i.created_at) >= CAST(#{from} AS timestamptz)
            """)
    Map<String, Object> defectHistory(@Param("productId") Long productId,
                                      @Param("defectKey") String defectKey,
                                      @Param("from") String from);

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
