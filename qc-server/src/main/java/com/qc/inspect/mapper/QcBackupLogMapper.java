package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.QcBackupLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface QcBackupLogMapper extends BaseMapper<QcBackupLog> {

    /**
     * 备份现状：最近一次尝试、最近一次成功、近 30 天的跑批计数。
     *
     * <p>三个刻意的写法：
     * <ul>
     *   <li>时间列一律 {@code to_char} 成字符串。返回 Map 时 MyBatis 不做类型映射，
     *       {@code timestamptz} 会被原样丢成 {@code Timestamp}，而本项目的
     *       {@code TimestamptzTypeHandler} 只作用于 LocalDateTime 目标类型，兜不住这里</li>
     *   <li>小时间隔在库里算。{@code now()} 是数据库的时钟，和写入时用的
     *       {@code created_at DEFAULT now()} 同一个源，不会因为应用服务器时区或时钟漂移
     *       算出「-1 小时前备份成功」这种鬼话</li>
     *   <li>空表时 {@code hours_since_success} 是 NULL 而不是 0。写成
     *       {@code greatest(0, ...)} 会让「从未成功过」显示成「0 小时前刚成功」——
     *       这是这个功能最不能犯的错</li>
     * </ul>
     */
    @Select("""
            SELECT (SELECT status     FROM qc_backup_log ORDER BY created_at DESC, id DESC LIMIT 1) AS last_status,
                   (SELECT message    FROM qc_backup_log ORDER BY created_at DESC, id DESC LIMIT 1) AS last_message,
                   (SELECT host       FROM qc_backup_log ORDER BY created_at DESC, id DESC LIMIT 1) AS last_host,
                   (SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS')
                      FROM qc_backup_log ORDER BY created_at DESC, id DESC LIMIT 1)                 AS last_attempt_at,
                   (SELECT floor(extract(epoch from (now() - created_at)) / 3600)::int
                      FROM qc_backup_log ORDER BY created_at DESC, id DESC LIMIT 1)                 AS hours_since_attempt,
                   (SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS')
                      FROM qc_backup_log WHERE status = 'SUCCESS'
                     ORDER BY created_at DESC, id DESC LIMIT 1)                                     AS last_success_at,
                   (SELECT floor(extract(epoch from (now() - max(created_at))) / 3600)::int
                      FROM qc_backup_log WHERE status = 'SUCCESS')                                  AS hours_since_success,
                   (SELECT file_size FROM qc_backup_log WHERE status = 'SUCCESS'
                     ORDER BY created_at DESC, id DESC LIMIT 1)                                     AS last_success_size,
                   (SELECT count(*) FROM qc_backup_log
                     WHERE created_at >= now() - interval '30 days')                                AS runs_30d,
                   (SELECT count(*) FROM qc_backup_log
                     WHERE status = 'FAILED' AND created_at >= now() - interval '30 days')           AS failures_30d,
                   (SELECT count(*) FROM qc_backup_log)                                             AS total_runs
            """)
    Map<String, Object> facts();
}
