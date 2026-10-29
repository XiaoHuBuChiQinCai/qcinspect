package com.qc.inspect.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 单号计数器。
 *
 * <p>取代原来「count(*) + 1」的取号方式：那种写法两个人同时开单会拿到同一个号，
 * 撞唯一索引后抛 PG 原始报错，用户看到的是 500 和一段英文。
 */
@Mapper
public interface QcNoSeqMapper {

    /**
     * 确保某个 prefix 的计数器存在，并把初始值对齐到已有的最大号。
     *
     * <p>只在第一次取号时真正插入，之后是 no-op。之所以每次取号都带上这一步，
     * 是为了自愈：手工清过计数器、或者某天有人在另一台机器上直接插过单据，
     * 都能自动对齐，不会从 1 开始和历史单撞号。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 让并发首用只有一个生效，其余静默跳过。
     */
    @Insert("""
            INSERT INTO qc_no_seq (prefix, last_no)
            SELECT CAST(#{prefix} AS text), CAST(#{seed} AS integer)
            ON CONFLICT (prefix) DO NOTHING
            """)
    int seed(@Param("prefix") String prefix, @Param("seed") long seed);

    /**
     * 取下一个号。
     *
     * <p>{@code UPDATE ... RETURNING} 在 PostgreSQL 里是原子的：同一 prefix 的并发请求
     * 会在行锁上排队，各自拿到不同的值。与单据插入同事务，回滚时一起回滚，
     * 既不留空号也不重复。
     */
    @Select("""
            UPDATE qc_no_seq
               SET last_no = last_no + 1,
                   updated_at = now()
             WHERE prefix = CAST(#{prefix} AS text)
            RETURNING last_no
            """)
    Long bump(@Param("prefix") String prefix);
}
