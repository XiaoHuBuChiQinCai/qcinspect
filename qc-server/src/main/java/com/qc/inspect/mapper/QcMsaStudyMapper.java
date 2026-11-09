package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcMsaStudy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QcMsaStudyMapper extends BaseMapper<QcMsaStudy> {

    /**
     * 当天已做的研究数量，作为单号种子。
     *
     * <p>占位符要显式 CAST 成 text：PostgreSQL 的 CONCAT 接受任意类型，
     * 没类型的占位符它推断不出来，直接报「无法确定参数的数据类型」。
     */
    @Select("SELECT count(*) FROM qc_msa_study WHERE study_no LIKE CONCAT('MSA', CAST(#{day} AS text), '%')")
    long countByDayPrefix(@Param("day") String day);
}
