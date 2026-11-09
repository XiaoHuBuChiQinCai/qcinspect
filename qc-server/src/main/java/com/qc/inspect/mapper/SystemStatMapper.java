package com.qc.inspect.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 系统级统计。和业务无关，只回答「这台机器还撑得住吗」。
 *
 * <p>刻意不放进 {@code QcImageMapper} 之类：这些查询跨表、甚至查的是
 * PostgreSQL 自己的 catalog，跟哪张业务表都不沾边。
 *
 * <p>返回 Map 时下划线转驼峰**不生效**（MyBatis 只在映射到实体时做转换），
 * 所以别名一律写小写下划线，Java 侧按同样的键取。
 */
@Mapper
public interface SystemStatMapper {

    /** 当前库占用的字节数（含索引与 TOAST） */
    @Select("SELECT pg_database_size(current_database())")
    long databaseSize();

    /**
     * 最大的几张表。
     *
     * <p>「数据库占了 8 GB」这句话本身没有下一步动作。指出是哪张表在涨，
     * 才知道该清日志、清历史检验单，还是照片走了库（本不该）。
     *
     * <p>用 {@code pg_stat_user_tables} 而不是 {@code pg_statio_user_tables}：
     * 行数在 stat 视图里，io 视图里没有 {@code n_live_tup}，写错了整段查询直接报字段不存在。
     * 行数是估算值（依赖 analyze），只用来让人对量级有个感觉，不参与任何判定。
     */
    @Select("""
            SELECT relname AS table_name,
                   pg_total_relation_size(relid) AS total_bytes,
                   n_live_tup AS live_rows
              FROM pg_catalog.pg_stat_user_tables
             ORDER BY pg_total_relation_size(relid) DESC
             LIMIT 5
            """)
    List<Map<String, Object>> largestTables();
}
