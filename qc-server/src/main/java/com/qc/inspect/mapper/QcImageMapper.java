package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface QcImageMapper extends BaseMapper<QcImage> {

    /**
     * 照片占用的总量与近期增量。
     *
     * <p>近期增量是用来估「还能撑几天」的唯一依据：只报「剩 2 GB」人不会紧张，
     * 报「按现在的速度还能撑 3 天」才有人今天就动手。
     *
     * <p>{@code file_size} 在 V1 里没有 NOT NULL，所以求和必须 coalesce——
     * 一张老照片的 size 是 NULL 就足以让整个 sum 变 NULL，
     * 页面上会显示成「照片占用 0」，比不显示更误导。
     *
     * <p>窗口天数用 {@code #{days}} 代入，必须 CAST 再乘 interval：
     * 裸参数进运算 PostgreSQL 认不出类型。
     */
    @Select("""
            SELECT count(*)                                                                    AS image_count,
                   coalesce(sum(file_size), 0)                                                 AS image_bytes,
                   coalesce(sum(file_size) FILTER (
                       WHERE created_at >= now() - CAST(#{days} AS int) * interval '1 day'), 0) AS recent_bytes
              FROM qc_image
            """)
    Map<String, Object> storageStats(@Param("days") int days);
}
