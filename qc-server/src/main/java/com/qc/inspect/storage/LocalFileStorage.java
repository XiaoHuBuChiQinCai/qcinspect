package com.qc.inspect.storage;

import com.qc.inspect.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘存储。对象键按 yyyy/MM/dd 分目录，避免单目录几十万文件。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "qc.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${qc.storage.local-dir:./data/files}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("创建本地存储目录失败 {}: {}", root, e.getMessage());
        }
        log.info("文件存储：本地目录 {}", root);
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw BizException.of("写文件失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] get(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.exists(target)) {
            throw BizException.of("文件不存在或已被清理");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw BizException.of("读文件失败：" + e.getMessage());
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            log.warn("删除文件失败 {}: {}", objectKey, e.getMessage());
        }
    }

    @Override
    public String presignedUrl(String objectKey) {
        // 本地模式由 ImageController 的 /raw 接口代理，返回 null 让业务层兜底
        return null;
    }

    private Path resolve(String objectKey) {
        Path p = root.resolve(objectKey).normalize();
        // 防目录穿越：对象键由服务端生成，但这里再校验一次
        if (!p.startsWith(root)) {
            throw BizException.of("非法的文件路径");
        }
        return p;
    }
}
