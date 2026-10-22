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

    /** 某批次累计已出货数量，用于拦住超发 */
    @Select("SELECT coalesce(sum(qty), 0) FROM qc_shipment WHERE batch_id = #{batchId} AND del_flag = 0")
    int shippedQty(@Param("batchId") Long batchId);
}
