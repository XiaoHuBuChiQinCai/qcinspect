package com.qc.inspect.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qc.inspect.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 记一次密码错误，够次数就地锁定。
     *
     * <p>整件事放在一条 UPDATE 里，是因为登录是唯一不需要登录态的接口——
     * 想猜密码的人可以并发打它。「先读次数、加一、再写回」在并发下会丢计数：
     * 十个请求同时读到 0，写回来的都是 1，于是「5 次锁定」变成了「5 次可能是一百次」。
     * 自增交给数据库，隔离级别和锁都不用自己操心。
     *
     * <p>锁定那一刻顺手把计数清零（而不是留着 5），否则期满后只要再错一次就立刻重新锁定，
     * 用户永远进不来。
     *
     * @param lockUntil 锁定截止时间，由调用方按 JVM 时钟算好传进来——
     *                  数据库和自己的时区不一定一致，混用 {@code now()} 会让「还剩几分钟」算错
     */
    @Update("""
            UPDATE sys_user
               SET last_failed_at  = #{now},
                   locked_until    = CASE WHEN failed_attempts + 1 >= #{maxFailures}
                                          THEN #{lockUntil} ELSE locked_until END,
                   failed_attempts = CASE WHEN failed_attempts + 1 >= #{maxFailures}
                                          THEN 0 ELSE failed_attempts + 1 END
             WHERE id = #{id}
            """)
    int countFailure(@Param("id") Long id,
                     @Param("now") LocalDateTime now,
                     @Param("lockUntil") LocalDateTime lockUntil,
                     @Param("maxFailures") int maxFailures);

    /**
     * 清零（登录成功、管理员解锁、管理员重设密码都走这里）。
     *
     * <p>用显式 SQL 而不是 {@code updateById}：后者忽略 null，
     * 写不出「把 locked_until 改回空」这一步。
     */
    @Update("UPDATE sys_user SET failed_attempts = 0, locked_until = NULL WHERE id = #{id}")
    int clearFailure(@Param("id") Long id);
}
