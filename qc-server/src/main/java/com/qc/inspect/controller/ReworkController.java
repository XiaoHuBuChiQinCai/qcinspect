package com.qc.inspect.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcReworkOrder;
import com.qc.inspect.service.ReworkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 返修工单。
 *
 * <p>开票、指派、开工、完工都对所有登录用户开放：返修票是车间的东西，
 * 检验员和返修工都得能填。只有「取消」限质量条线——取消等于把票作废，
 * 把件从账上抹掉，不能让随手一点就没了。
 */
@RestController
@RequestMapping("/api/rework")
@RequiredArgsConstructor
public class ReworkController {

    private final ReworkService reworkService;

    /**
     * 返修票分页。keyword 同时匹配票号、不良名称、返修人、批次号——
     * 车间问的一般是「上次那个划伤的修完了吗」或者「三号台手上还有哪些」。
     */
    @GetMapping("/page")
    public R<Page<QcReworkOrder>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) Boolean overdue) {
        return R.ok(reworkService.page(current, size, status, keyword, from, to, overdue));
    }

    /** 待返修/返修中，超期在前。手机待办与工作台角标都用它 */
    @GetMapping("/pending")
    public R<List<QcReworkOrder>> pending(@RequestParam(required = false, defaultValue = "50") int limit) {
        return R.ok(reworkService.pending(limit));
    }

    /** 某张检验单下的返修票，检验单详情页嵌入用 */
    @GetMapping("/by-inspection/{inspectionId}")
    public R<List<QcReworkOrder>> byInspection(@PathVariable Long inspectionId) {
        return R.ok(reworkService.listByInspection(inspectionId));
    }

    @GetMapping("/{id}")
    public R<QcReworkOrder> detail(@PathVariable Long id) {
        return R.ok(reworkService.detail(id));
    }

    /** 从一条不良开票。数量不传时按不良记录的件数 */
    @PostMapping
    public R<QcReworkOrder> create(@RequestBody CreateReq req) {
        return R.ok(reworkService.create(req.defectId(), req.qty(), req.requirement(),
                req.assignee(), parseDate(req.dueDate()), req.remark()));
    }

    /** 改派或补填返修人；dueDate 传了才改期限 */
    @PostMapping("/{id}/assign")
    public R<QcReworkOrder> assign(@PathVariable Long id, @RequestBody AssignReq req) {
        return R.ok(reworkService.assign(id, req.assignee(), parseDate(req.dueDate())));
    }

    @PostMapping("/{id}/start")
    public R<QcReworkOrder> start(@PathVariable Long id) {
        return R.ok(reworkService.start(id));
    }

    /** 完工报数：返好 + 返废 必须等于送修数量。返好 > 0 时自动开复检单 */
    @PostMapping("/{id}/finish")
    public R<Map<String, Object>> finish(@PathVariable Long id, @RequestBody FinishReq req) {
        return R.ok(reworkService.finish(id, req.reworkedQty(), req.scrappedQty(), req.finishNote()));
    }

    /** 取消票。理由必填，且限质量条线——这是把件从账上抹掉的动作 */
    @PostMapping("/{id}/cancel")
    public R<QcReworkOrder> cancel(@PathVariable Long id, @RequestBody CancelReq req) {
        if (!UserContext.canReview()) {
            throw BizException.of("取消返修票会让这批件从账上消失，只有管理员和审核员能操作");
        }
        return R.ok(reworkService.cancel(id, req.reason()));
    }

    /**
     * 日期字符串转 LocalDate。
     *
     * <p>空串一律当「没填」而不是报错：前端清空日期控件时传的是空串，
     * 报错会让人以为是自己填错了。
     */
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of("日期格式不对（" + s + "），要的是 2026-11-09 这种");
        }
    }

    // 请求体都用 record：字段少，不值得单独建 DTO 类

    public record CreateReq(Long defectId, Integer qty, String requirement,
                            String assignee, String dueDate, String remark) {
    }

    public record AssignReq(String assignee, String dueDate) {
    }

    public record FinishReq(Integer reworkedQty, Integer scrappedQty, String finishNote) {
    }

    public record CancelReq(String reason) {
    }
}
