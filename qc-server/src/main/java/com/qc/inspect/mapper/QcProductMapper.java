package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QcProductMapper extends BaseMapper<QcProduct> {

    /**
     * 产品一览：带上标准数量与批次使用量。
     *
     * <p>为什么这个字段值得单独做一个接口：产品建好但没有启用标准，
     * 到了产线「开检验单」那一刻才报错——那时候人在车间、料已经卸了。
     * 把「有没有启用标准」摆在产品列表上，缺什么一眼就能看出来。
     *
     * <p>批次使用量是给删改前的提示用的：已经在用的产品改编号，
     * 历史单据上的快照不会跟着变，得让操作的人先知道代价。
     *
     * <p>三个子查询都是单键聚合，走索引，产品表顶多几百行，不值得做联表分组。
     */
    @Select("""
            SELECT p.id,
                   p.product_code,
                   p.product_name,
                   p.spec,
                   p.unit,
                   p.remark,
                   (SELECT count(*) FROM qc_standard s
                     WHERE s.product_id = p.id AND s.del_flag = 0) AS standard_count,
                   (SELECT count(*) FROM qc_standard s
                     WHERE s.product_id = p.id AND s.del_flag = 0 AND s.status = 'ACTIVE') AS active_standard_count,
                   (SELECT count(*) FROM qc_batch b
                     WHERE b.product_id = p.id AND b.del_flag = 0) AS batch_count
              FROM qc_product p
             WHERE p.del_flag = 0
               AND (CAST(#{kw} AS text) IS NULL
                    OR p.product_code LIKE CONCAT('%', CAST(#{kw} AS text), '%')
                    OR p.product_name LIKE CONCAT('%', CAST(#{kw} AS text), '%'))
             ORDER BY p.id DESC
            """)
    List<Map<String, Object>> overview(@Param("kw") String keyword);
}
