package com.qc.inspect.storage;

import com.qc.inspect.common.BizException;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "qc.storage.type", havingValue = "minio")
public class MinioFileStorage implements FileStorage {

    private final MinioClient client;
    private final String bucket;
    private final int urlExpireDays;

    public MinioFileStorage(@Value("${qc.minio.endpoint}") String endpoint,
                            @Value("${qc.minio.access-key}") String accessKey,
                            @Value("${qc.minio.secret-key}") String secretKey,
                            @Value("${qc.minio.bucket}") String bucket,
                            @Value("${qc.minio.url-expire-days:7}") int urlExpireDays) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.urlExpireDays = urlExpireDays;
    }

    @PostConstruct
    void init() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO 桶 {}", bucket);
            }
            log.info("文件存储：MinIO bucket={}", bucket);
        } catch (Exception e) {
            // 不阻断启动：MinIO 挂了业务系统仍要能用，只是传图会报错
            log.error("MinIO 初始化失败，图片上传将不可用：{}", e.getMessage());
        }
    }

    @Override
    public String type() {
        return "minio";
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw BizException.of("上传到对象存储失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectKey).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw BizException.of("读取对象存储失败：" + e.getMessage());
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("删除对象失败 {}: {}", objectKey, e.getMessage());
        }
    }

    @Override
    public String presignedUrl(String objectKey) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(urlExpireDays, TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            log.warn("生成预签名地址失败 {}: {}", objectKey, e.getMessage());
            return null;
        }
    }
}
