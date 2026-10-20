package com.qc.inspect.controller;

import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcNotice;
import com.qc.inspect.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 站内通知。移动端在待办页顶部取未读数，PC 端在顶栏铃铛取列表。
 */
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    /** 我的通知；unreadOnly=true 只看未读 */
    @GetMapping
    public R<List<QcNotice>> list(@RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
                                  @RequestParam(required = false, defaultValue = "20") int limit) {
        return R.ok(noticeService.list(UserContext.username(), unreadOnly, limit));
    }

    /** 未读数，供角标轮询 */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(noticeService.summary(UserContext.username()));
    }

    @PostMapping("/{id}/read")
    public R<Void> read(@PathVariable Long id) {
        noticeService.read(id, UserContext.username());
        return R.ok();
    }

    @PostMapping("/read-all")
    public R<Map<String, Object>> readAll() {
        int n = noticeService.readAll(UserContext.username());
        return R.ok(Map.of("updated", n));
    }
}
