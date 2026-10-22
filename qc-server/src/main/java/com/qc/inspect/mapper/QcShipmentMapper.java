package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcShipment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QcShipmentMapper extends BaseMapper<QcShipment> {

    /**
     * 当天已开的出货单数量（含已逻辑删除，避免单号复用）。
     *
     * <p>和建检验单一样，这里要把占位符显式 CAST 成 text：
     * PostgreSQL 的 CONCAT 接受任意类型，没类型的占位符它无法推断，
     * 直接报「无法确定参数 $1 的数据类型」，接口 500。
     */
    @Select("SELECT count(*) FROM qc_shipment WHERE shipment_no LIKE CONCAT('SH', CAST(#{day} AS text), '%')")
    long countByDayPrefix(@Param("day") String day);

    /**
     * 某批次累计已出货数量，用于拦住超发。
     *
     * <p>必须排除已作废的单：作废的意义就是「这单不算数」，
     * 如果作废后还占着批次数量的额度，等于作废完了仍然出不了第二单。
     *
     * <p>{@code coalesce(status, 'NORMAL')} 是给 V9 之前的存量行兜底——
     * 那些行在加列时会被 DEFAULT 填成 NORMAL，这里再兜一层防止手工插的数据为 NULL。
     */
    @Select("SELECT coalesce(sum(qty), 0) FROM qc_shipment"
            + " WHERE batch_id = #{batchId} AND del_flag = 0"
            + " AND coalesce(status, 'NORMAL') <> 'VOID'")
    int shippedQty(@Param("batchId") Long batchId);
}
