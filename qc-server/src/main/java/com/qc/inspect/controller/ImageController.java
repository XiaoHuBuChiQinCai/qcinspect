package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.dto.ImageView;
import com.qc.inspect.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    /** 上传：bizType 取 INSPECTION / DEFECT / GAUGE */
    @PostMapping
    public R<ImageView> upload(@RequestParam String bizType,
                               @RequestParam Long bizId,
                               @RequestParam("file") MultipartFile file) {
        return R.ok(imageService.upload(bizType, bizId, file));
    }

    @GetMapping
    public R<List<ImageView>> list(@RequestParam String bizType, @RequestParam Long bizId) {
        return R.ok(imageService.list(bizType, bizId));
    }

    /** 本地存储模式下由这里代理出图；<img> 标签带不了 Authorization 头，所以放行 */
    @GetMapping("/{id}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable Long id) {
        ImageService.RawFile f = imageService.raw(id);
        String encoded = URLEncoder.encode(f.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=604800")
                .body(f.bytes());
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        imageService.delete(id);
        return R.ok();
    }
}
