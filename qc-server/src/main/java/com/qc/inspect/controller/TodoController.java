package com.qc.inspect.controller;

import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcInspection;
import com.qc.inspect.service.TodoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 待办。手机端首页和工作台角标都从这里取数。
 */
@RestController
@RequestMapping("/api/todo")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    /** 五个数字：待审核、待处置、超期量具、我的草稿、我被退回 */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(todoService.summary(UserContext.username()));
    }

    /** 待审核单据；limit 默认 50，工作台只看头部，全量去检验单列表筛 */
    @GetMapping("/reviews")
    public R<List<QcInspection>> reviews(@RequestParam(required = false, defaultValue = "50") int limit) {
        return R.ok(todoService.pendingReviews(limit));
    }

    /** 待处置不良（含所属检验单号，省得前端再查一次） */
    @GetMapping("/defects")
    public R<List<Map<String, Object>>> defects() {
        return R.ok(todoService.pendingDefects());
    }

    /** 我的草稿与被退回的单 */
    @GetMapping("/mine")
    public R<Map<String, Object>> mine() {
        return R.ok(todoService.mine(UserContext.username()));
    }

    /** 超期未校准量具 */
    @GetMapping("/gauges")
    public R<List<Map<String, Object>>> gauges() {
        return R.ok(todoService.overdueGauges());
    }
}
