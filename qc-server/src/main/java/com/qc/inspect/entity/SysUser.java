package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
