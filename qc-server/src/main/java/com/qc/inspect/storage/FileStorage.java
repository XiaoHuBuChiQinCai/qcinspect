package com.qc.inspect.storage;

/**
 * 文件存储抽象。默认落本地磁盘，配 qc.storage.type=minio 后走对象存储。
 * <p>
 * 之所以两种都给：这个项目在单厂内网部署时 MinIO 是额外运维成本，
 * 本地目录 + 定期备份反而更省事；但图片量上来后又要能平滑切到 MinIO。
 */
public interface FileStorage {

    String type();

    /** 写入，返回对象键 */
    void put(String objectKey, byte[] bytes, String contentType);

    byte[] get(String objectKey);

    void delete(String objectKey);

    /** 浏览器可直接访问的地址；返回 null 表示由业务层用 /raw 接口代理 */
    String presignedUrl(String objectKey);
}
