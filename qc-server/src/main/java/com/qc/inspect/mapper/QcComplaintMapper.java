package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcComplaint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QcComplaintMapper extends BaseMapper<QcComplaint> {

    /**
     * 当天已开的客诉单数量（含已逻辑删除，避免单号复用）。
     *
     * <p>占位符必须显式 CAST 成 text：PostgreSQL 的 CONCAT 接受任意类型，
     * 没类型的占位符它推断不出来，直接报「无法确定参数 $1 的数据类型」，接口 500。
     */
    @Select("SELECT count(*) FROM qc_complaint WHERE complaint_no LIKE CONCAT('TS', CAST(#{day} AS text), '%')")
    long countByDayPrefix(@Param("day") String day);

    /**
     * 某批次未关闭的客诉数。
     *
     * <p>出货前要拦这一道：客户已经投诉了这批货，还没查清楚又接着发，
     * 等于把同一个问题复制给第二个客户。内部不良品有 qc_defect 闭环拦着，
     * 客户侧的反馈同样得拦。
     */
    @Select("SELECT count(*) FROM qc_complaint"
            + " WHERE batch_id = #{batchId} AND del_flag = 0 AND status <> 'CLOSED'")
    long countOpen(@Param("batchId") Long batchId);

    /** 按严重度统计，用于工作台与统计页 */
    @Select("SELECT coalesce(sum(CASE WHEN severity = 'HIGH' THEN 1 ELSE 0 END), 0) AS high,"
            + " coalesce(sum(CASE WHEN severity = 'MEDIUM' THEN 1 ELSE 0 END), 0) AS medium,"
            + " coalesce(sum(CASE WHEN severity = 'LOW' THEN 1 ELSE 0 END), 0) AS low,"
            + " coalesce(sum(CASE WHEN status <> 'CLOSED' THEN 1 ELSE 0 END), 0) AS open"
            + " FROM qc_complaint WHERE del_flag = 0")
    java.util.Map<String, Object> summary();
}
