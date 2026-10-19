package com.qc.inspect.dto;

import com.qc.inspect.entity.QcImage;

import java.time.LocalDateTime;

/**
 * 图片返回结构。url 由存储层决定：MinIO 给预签名地址，本地存储给 /raw 代理地址。
 */
public record ImageView(Long id, String bizType, Long bizId, String fileName, String contentType,
                        Long fileSize, String url, String createdBy, LocalDateTime createdAt) {

    public static ImageView of(QcImage img, String url) {
        return new ImageView(img.getId(), img.getBizType(), img.getBizId(), img.getFileName(),
                img.getContentType(), img.getFileSize(), url, img.getCreatedBy(), img.getCreatedAt());
    }
}
