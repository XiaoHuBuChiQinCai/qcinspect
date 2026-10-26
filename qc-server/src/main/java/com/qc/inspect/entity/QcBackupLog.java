package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次数据库备份的执行记录。
 *
 * <p>由 {@code scripts/backup.sh} 写入，应用只读。之所以要落库而不是留在日志里：
 * 备份坏得很安静——timer 照样每天触发，真正能回答「上次成功是什么时候」的
 * 只有这张表。
 */
@Data
@TableName("qc_backup_log")
public class QcBackupLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** SUCCESS / FAILED。没有「进行中」——结果才有意义 */
    private String status;

    private String fileName;

    /** 压缩后字节数。比时间更早暴露异常：突然小一个量级说明 dump 没导全 */
    private Long fileSize;

    /** 本次清理掉的过期备份数 */
    private Integer prunedFiles;

    /** 执行备份的机器名 */
    private String host;

    private String message;

    private LocalDateTime createdAt;
}
