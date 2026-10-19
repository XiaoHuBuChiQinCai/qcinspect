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

    @TableLogic
    private Integer delFlag;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
