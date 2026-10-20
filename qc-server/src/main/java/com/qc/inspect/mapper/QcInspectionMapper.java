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
     * 缺陷分布（帕累托数据源）。
     */
    @Select("""
            SELECT defect_name, count(*) AS cnt, sum(defect_qty) AS qty
            FROM qc_defect
            GROUP BY defect_name
            ORDER BY cnt DESC
            """)
    List<Map<String, Object>> statDefect();
}
