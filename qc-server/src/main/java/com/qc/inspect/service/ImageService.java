package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qc.inspect.auth.ImageToken;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.dto.ImageView;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcImage;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.entity.QcInspectionLog;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcImageMapper;
import com.qc.inspect.mapper.QcInspectionLogMapper;
import com.qc.inspect.mapper.QcInspectionMapper;
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
    private final QcInspectionMapper inspectionMapper;
    private final QcDefectMapper defectMapper;
    private final QcInspectionLogMapper logMapper;
    private final FileStorage storage;
    private final ImageToken imageToken;

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

    /**
     * 取原图。不要求登录态，但要求签名有效——见 {@link ImageToken} 的说明：
     * id 是自增的，只靠「未登录不给」挡不住遍历。
     *
     * <p>先验签再查库：签名不对就一律报「链接无效」，不能让调用方通过返回信息的
     * 差异判断某个 id 到底存不存在。
     */
    public RawFile raw(Long id, Long exp, String sig) {
        if (!imageToken.verify(id, exp, sig)) {
            throw BizException.of("图片链接无效或已过期，请刷新页面");
        }
        QcImage img = imageMapper.selectById(id);
        if (img == null) {
            throw BizException.of("图片不存在");
        }
        return new RawFile(storage.get(img.getObjectKey()), img.getContentType(),
                img.getFileName() == null ? "image" : img.getFileName());
    }

    /**
     * 删除照片。
     *
     * <p>照片是质检的客观证据，删掉就没了（存储里是硬删，不占双份空间）。所以这里不是
     * 「随手删一张图」，而是一个要讲清理由的动作，三条限制：
     *
     * <ol>
     *   <li><b>单据已提交就不许删</b>：判定结论连同照片一起构成证据链，结论已经出去了、
     *       照片却还能被抹掉，等于证据可以被事后修饰。要更正必须先走退回（reopen）。</li>
     *   <li><b>非管理员只能删自己传的</b>：不然谁都能去别人单子上挑照片删。</li>
     *   <li><b>删了要留痕</b>：写进该检验单的操作日志，全局审计检索能看到谁在什么时候
     *       删了哪张图。文件本身不保留，但「有人删过」这个事实必须留住。</li>
     * </ol>
     */
    @Transactional
    public void delete(Long id) {
        QcImage img = imageMapper.selectById(id);
        if (img == null) {
            // 幂等：目标状态已经达成，不必报错。重试删除不该失败
            return;
        }

        Long inspectionId = inspectionIdOf(img);
        QcInspection ins = inspectionId == null ? null : inspectionMapper.selectById(inspectionId);
        if (ins != null && !"DRAFT".equals(ins.getStatus())) {
            throw BizException.of("检验单 " + ins.getInspectionNo() + " 已"
                    + statusText(ins.getStatus())
                    + "，照片是判定证据，不能再删；需要更正请先退回草稿");
        }

        String me = UserContext.username();
        if (!UserContext.isAdmin() && img.getCreatedBy() != null && !img.getCreatedBy().equals(me)) {
            throw BizException.of("只能删除自己上传的照片");
        }

        storage.delete(img.getObjectKey());
        imageMapper.deleteById(id);

        if (inspectionId != null && ins != null) {
            String where = "DEFECT".equals(img.getBizType())
                    ? "不良记录 " + img.getBizId() + " 的照片"
                    : "照片";
            QcInspectionLog row = new QcInspectionLog();
            row.setInspectionId(inspectionId);
            row.setAction("IMAGE_DELETE");
            row.setFromStatus(ins.getStatus());
            row.setToStatus(ins.getStatus());
            row.setOperator(me);
            row.setSummary("删除" + where + "：" + img.getFileName());
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } else {
            // 量具照片没有归属单据，落不到操作日志里，至少让运维能在应用日志查到
            log.info("删除量具照片 id={} file={} by={}", id, img.getFileName(), me);
        }
    }

    /** 照片挂在哪张检验单上。不良记录要先经 qc_defect 转到它的检验单 */
    private Long inspectionIdOf(QcImage img) {
        if ("INSPECTION".equals(img.getBizType())) {
            return img.getBizId();
        }
        if ("DEFECT".equals(img.getBizType())) {
            QcDefect d = defectMapper.selectById(img.getBizId());
            return d == null ? null : d.getInspectionId();
        }
        return null;
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "SUBMITTED" -> "提交待审";
            case "CLOSED" -> "审核归档";
            case "VOID" -> "作废";
            default -> status;
        };
    }

    private ImageView toView(QcImage img) {
        String url = storage.presignedUrl(img.getObjectKey());
        if (url == null) {
            // 本地存储：地址必须带签名，否则匿名遍历 id 就能把全厂照片拉走
            url = "/api/images/" + img.getId() + "/raw" + imageToken.queryOf(img.getId());
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
