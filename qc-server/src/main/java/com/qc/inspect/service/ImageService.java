package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.dto.ImageView;
import com.qc.inspect.entity.QcImage;
import com.qc.inspect.mapper.QcImageMapper;
import com.qc.inspect.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "bmp");
    private static final Set<String> ALLOWED_BIZ = Set.of("INSPECTION", "DEFECT", "GAUGE");
    private static final long MAX_SIZE = 10L * 1024 * 1024;

    private final QcImageMapper imageMapper;
    private final FileStorage storage;

    @Transactional
    public ImageView upload(String bizType, Long bizId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.of("请选择要上传的文件");
        }
        if (bizType == null || !ALLOWED_BIZ.contains(bizType)) {
            throw BizException.of("业务类型不合法：" + bizType);
        }
        if (bizId == null || bizId <= 0) {
            throw BizException.of("业务 ID 不合法");
        }
        if (file.getSize() > MAX_SIZE) {
            throw BizException.of("单张图片不能超过 10MB");
        }

        String original = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        String ext = extOf(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.of("只支持 " + String.join(" / ", ALLOWED_EXT) + " 格式，当前：" + ext);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.of("读取上传文件失败：" + e.getMessage());
        }
        // 不信浏览器给的 Content-Type，按扩展名映射
        String contentType = switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };

        String objectKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;

        storage.put(objectKey, bytes, contentType);

        QcImage img = new QcImage();
        img.setBizType(bizType);
        img.setBizId(bizId);
        img.setFileName(original);
        img.setObjectKey(objectKey);
        img.setFileSize(file.getSize());
        img.setContentType(contentType);
        img.setCreatedBy(UserContext.username());
        img.setCreatedAt(LocalDateTime.now());
        imageMapper.insert(img);

        log.debug("图片已上传 {} → {} ({})", original, objectKey, storage.type());
        return toView(img);
    }

    public List<ImageView> list(String bizType, Long bizId) {
        return imageMapper.selectList(Wrappers.<QcImage>lambdaQuery()
                        .eq(QcImage::getBizType, bizType)
                        .eq(QcImage::getBizId, bizId)
                        .orderByAsc(QcImage::getId))
                .stream().map(this::toView).toList();
    }

    public RawFile raw(Long id) {
        QcImage img = imageMapper.selectById(id);
        if (img == null) {
            throw BizException.of("图片不存在");
        }
        return new RawFile(storage.get(img.getObjectKey()), img.getContentType(),
                img.getFileName() == null ? "image" : img.getFileName());
    }

    @Transactional
    public void delete(Long id) {
        QcImage img = imageMapper.selectById(id);
        if (img == null) {
            return;
        }
        storage.delete(img.getObjectKey());
        imageMapper.deleteById(id);
    }

    private ImageView toView(QcImage img) {
        String url = storage.presignedUrl(img.getObjectKey());
        if (url == null) {
            url = "/api/images/" + img.getId() + "/raw";
        }
        return ImageView.of(img, url);
    }

    private String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record RawFile(byte[] bytes, String contentType, String fileName) {
    }
}
