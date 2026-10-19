package com.qc.inspect.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qc_image")
public class QcImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** INSPECTION / DEFECT */
    private String bizType;

    private Long bizId;
    private String fileName;
    private String objectKey;
    private Long fileSize;
    private String contentType;
    private String createdBy;
    private LocalDateTime createdAt;
}
