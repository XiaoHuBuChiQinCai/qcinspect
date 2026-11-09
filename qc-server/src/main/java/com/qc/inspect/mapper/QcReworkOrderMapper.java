package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcReworkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcReworkOrderMapper extends BaseMapper<QcReworkOrder> {

    /**
     * 当天已开的返修票数量，作为单号种子。
     *
     * <p>占位符必须显式 CAST 成 text：PostgreSQL 的 CONCAT 接受任意类型，
     * 没类型的占位符它推断不出来，直接报「无法确定参数的数据类型」，接口 500。
     */
    @Select("SELECT count(*) FROM qc_rework_order WHERE rework_no LIKE CONCAT('RW', CAST(#{day} AS text), '%')")
    long countByDayPrefix(@Param("day") String day);

    /**
     * 按产品统计返修与返废。
     *
     * <p>这个数在合格率报表上是看不到的：返废件从来没被判定过，它是在返修台上坏的。
     * 不单独统计，返工损耗就永远是车间里的口头数字。
     */
    @Select("""
            SELECT o.product_id                    AS product_id,
                   coalesce(sum(o.qty), 0)          AS sent_qty,
                   coalesce(sum(o.reworked_qty), 0) AS reworked_qty,
                   coalesce(sum(o.scrapped_qty), 0) AS scrapped_qty,
                   count(*)                         AS orders
            FROM qc_rework_order o
            WHERE o.del_flag = 0
              AND o.status = 'DONE'
              AND o.finished_at >= CAST(#{from} AS timestamptz)
              AND o.finished_at <  CAST(#{to} AS timestamptz)
            GROUP BY o.product_id
            ORDER BY sum(o.scrapped_qty) DESC
            """)
    List<Map<String, Object>> scrapByProduct(@Param("from") String from, @Param("to") String to);
}
