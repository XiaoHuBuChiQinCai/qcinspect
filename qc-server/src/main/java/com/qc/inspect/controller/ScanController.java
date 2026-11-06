package com.qc.inspect.controller;

import com.qc.inspect.common.R;
import com.qc.inspect.service.ScanCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 扫码（拍照识别）。
 *
 * <p>单独一个控制器而不是塞进 /api/images：那个是「把照片存起来挂到单据上」，
 * 这个是「看一眼就扔」，不落库、不进对象存储、不算附件。
 */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanCodeService scanCodeService;

    /** 传一张图，返回识别出的文本与码型。认不出返回业务错误，前端照原样提示 */
    @PostMapping("/decode")
    public R<ScanCodeService.ScanResult> decode(@RequestParam("file") MultipartFile file) {
        return R.ok(scanCodeService.decode(file));
    }
}
