package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希，永不返回给前端 */
    @JsonIgnore
    private String password;

    private String realName;

    /** ADMIN / INSPECTOR */
    private String role;

    private Boolean enabled;

    /**
     * 企业微信 UserID（通讯录里的账号，不是手机号也不是姓名）。
     *
     * <p>留空是合法状态：这个人只收站内通知。做成人工填空而不是按姓名自动匹配，
     * 是因为工厂里重名不是小概率事件——自动匹配歪了就是把别人的退回意见推给不相干的人，
     * 比不推更糟。
     */
    private String wecomUserid;

    /** 连续密码错误次数；达到上限即锁定，并在锁定的同时清零（解锁后重新给满机会） */
    private Integer failedAttempts;

    /** 锁定截止时间。为空表示未锁定；过期自动放行，不需要人操作 */
    private LocalDateTime lockedUntil;

    private LocalDateTime lastFailedAt;

    /**
     * 现在是否锁着。瞬态字段，不落库——由服务端按 {@code lockedUntil} 与当前时间算好再返回。
     *
     * <p>不让前端自己比时间：浏览器与服务器的时钟差几分钟是常事，
     * 差的这几分钟会让「页面显示已锁定、提交却能过」这种矛盾状态出现。
     */
    @TableField(exist = false)
    private Boolean locked;

    /** 还剩几分钟解锁（已解锁为 null）。同上，服务端算 */
    @TableField(exist = false)
    private Long lockMinutes;

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
