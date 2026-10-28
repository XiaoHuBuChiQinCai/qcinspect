package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcStaleReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcStaleReviewMapper extends BaseMapper<QcStaleReview> {

    /**
     * 一批作废版本各自最近一次复核。
     *
     * <p>为什么按 id 列表查而不是按产品查：调用方是 stale-usage，它有时按产品筛、
     * 有时是全库视角（工作台），两种情况下手上都只有「这一批 standard_id」。
     * 按产品查会漏掉全库视角，按 id 查两种都能用。
     *
     * <p>{@code DISTINCT ON} 是 PostgreSQL 的写法：先按 {@code standard_id} 分组、
     * 组内按时间倒序取第一条。换成 {@code GROUP BY + max(reviewed_at)} 再 JOIN 回来
     * 也能写，但同一秒内的两次复核会挑错人。
     *
     * <p>别名一律下划线：MyBatis 返回 Map 时驼峰转换不生效，键就是原始列标签。
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (r.standard_id)
                   r.standard_id,
                   r.verdict,
                   r.remark,
                   r.reviewer,
                   to_char(r.reviewed_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS reviewed_at
            FROM qc_stale_review r
            WHERE r.del_flag = 0
              AND r.standard_id IN
              <foreach collection="ids" item="sid" open="(" separator="," close=")">#{sid}</foreach>
            ORDER BY r.standard_id, r.reviewed_at DESC, r.id DESC
            </script>
            """)
    List<Map<String, Object>> lastReviewsOf(@Param("ids") List<Long> ids);

    /**
     * 某作废版本的全部复核记录，最新在前。
     *
     * <p>点开「复核记录」看的是「这件事处理过几轮、每轮怎么说的」，
     * 所以只给最近一次是不够的。
     */
    @Select("""
            SELECT * FROM qc_stale_review
            WHERE standard_id = #{standardId} AND del_flag = 0
            ORDER BY reviewed_at DESC, id DESC
            """)
    List<QcStaleReview> listOfStandard(@Param("standardId") Long standardId);
}
