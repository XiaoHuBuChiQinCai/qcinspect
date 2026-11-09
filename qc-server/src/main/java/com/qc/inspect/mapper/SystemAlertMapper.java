package com.qc.inspect.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 系统预警的推送去重。
 *
 * <p>表很小、只有一个用途：记「今天这个告警推过了没有」。
 */
@Mapper
public interface SystemAlertMapper {

    /**
     * 占用今天这一格。返回 1 表示抢到了，可以推；返回 0 表示今天已经推过。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而不是「先查再插」：应用重启、
     * 定时任务被手工触发、两台机器同时跑，这几种情况下「先查再插」都会推两遍。
     */
    @Insert("""
            INSERT INTO qc_system_alert (alert_key, alert_date, level, message)
            VALUES (#{key}, CURRENT_DATE, #{level}, #{message})
            ON CONFLICT (alert_key, alert_date) DO NOTHING
            """)
    int claim(@Param("key") String key, @Param("level") String level, @Param("message") String message);
}
