package com.qc.inspect.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.QcCapa;
import com.qc.inspect.service.CapaService;
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
 * 纠正预防措施。
 *
 * <p>权限分两档，理由不一样：
 * <ul>
 *   <li>建、开始、填措施、提交验证——<b>责任人自己能做</b>。这些是他的活，
 *       非让他找管理员代劳，措施就会卡在「等人帮我点一下」</li>
 *   <li>验证与撤销——要质量条线。验证是别人来判你有没有用，不能自己来</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/capa")
@RequiredArgsConstructor
public class CapaController {

    private final CapaService capaService;

    /** 建措施。来源是不良时，产品与不良标识由服务端按来源覆盖，不信前端传的 */
    @PostMapping
    public R<QcCapa> create(@RequestBody CreateBody body) {
        QcCapa req = new QcCapa();
        req.setSourceType(body.sourceType());
        req.setSourceId(body.sourceId());
        req.setSourceRef(body.sourceRef());
        req.setTitle(body.title());
        req.setProblem(body.problem());
        req.setProductId(body.productId());
        req.setDefectCode(body.defectCode());
        req.setSeverity(body.severity());
        req.setRootCause(body.rootCause());
        req.setActionPlan(body.actionPlan());
        req.setPreventPlan(body.preventPlan());
        req.setOwner(body.owner());
        req.setDueDate(parseDate(body.dueDate()));
        req.setRemark(body.remark());
        return R.ok(capaService.create(req));
    }

    @PostMapping("/{id}/start")
    public R<QcCapa> start(@PathVariable Long id) {
        requireOwnerOrQuality(id);
        return R.ok(capaService.start(id));
    }

    /** 保存根因与措施。进了「待验证」就冻结，要改得让验证人判无效退回来 */
    @PostMapping("/{id}/analysis")
    public R<QcCapa> analysis(@PathVariable Long id, @RequestBody AnalysisBody body) {
        requireOwnerOrQuality(id);
        return R.ok(capaService.saveAnalysis(id, body.rootCause(), body.actionPlan(), body.preventPlan()));
    }

    @PostMapping("/{id}/submit-verify")
    public R<QcCapa> submitVerify(@PathVariable Long id) {
        requireOwnerOrQuality(id);
        return R.ok(capaService.submitVerify(id));
    }

    /**
     * 验证。有效则关闭，无效则退回「措施执行中」。
     *
     * <p>只允许质量条线调用；服务层还会再拦一次「责任人不能验自己」。
     * 两道判断看着重复，其实不是：这里的 {@code canReview} 挡的是「谁有资格验」，
     * 服务层挡的是「这一步不能自己给自己盖章」。绕过接口直接发请求也过不了第二道。
     */
    @PostMapping("/{id}/verify")
    public R<QcCapa> verify(@PathVariable Long id, @RequestBody VerifyBody body) {
        if (!UserContext.canReview()) {
            throw BizException.of("只有审核员或管理员能验证措施。措施有没有用，得由没参与它的人来判断");
        }
        return R.ok(capaService.verify(id, body.effective(), body.note()));
    }

    @PostMapping("/{id}/cancel")
    public R<QcCapa> cancel(@PathVariable Long id, @RequestBody CancelBody body) {
        if (!UserContext.canReview()) {
            throw BizException.of("只有审核员或管理员能撤销措施");
        }
        return R.ok(capaService.cancel(id, body.reason()));
    }

    @GetMapping("/page")
    public R<Page<QcCapa>> page(@RequestParam(defaultValue = "1") long current,
                                @RequestParam(defaultValue = "10") long size,
                                @RequestParam(required = false) String status,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(required = false) Boolean overdue,
                                @RequestParam(required = false) String owner) {
        return R.ok(capaService.page(current, size, status, keyword, overdue, owner));
    }

    @GetMapping("/pending")
    public R<List<QcCapa>> pending(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(capaService.pending(limit));
    }

    @GetMapping("/{id}")
    public R<QcCapa> detail(@PathVariable Long id) {
        return R.ok(capaService.detail(id));
    }

    /**
     * 建措施之前的「以前犯过几次」。
     *
     * <p>这是整块功能里最值一提的接口：它回答的是「这次要不要真的查根因」。
     * 第一次出现可以只做纠正，第四次出现还只做纠正，就是在拿返工当解决办法。
     */
    @GetMapping("/recurrence")
    public R<Map<String, Object>> recurrence(@RequestParam(required = false) Long productId,
                                             @RequestParam(required = false) String defectKey) {
        return R.ok(capaService.recurrence(productId, defectKey));
    }

    /**
     * 该立措施却还没立的：近 90 天反复出现、当前没有有效措施的不良簇。
     *
     * <p>这是「动作层」的入口。{@code /recurrence} 要人先想到要立措施才会被查，
     * 而这个接口是系统主动把单子推到人面前——返回里已经带好建单要用的标题与问题描述，
     * 点一下就进建单弹窗。
     *
     * <p>不做权限限制：信息本身不敏感，建单那一步本来就要填责任人、走原来的校验。
     */
    @GetMapping("/suggestions")
    public R<List<Map<String, Object>>> suggestions() {
        return R.ok(capaService.suggestions());
    }

    /** 按责任人看谁手上压着几条 */
    @GetMapping("/by-owner")
    public R<List<Map<String, Object>>> byOwner() {
        return R.ok(capaService.openByOwner());
    }

    /**
     * 改内容的门槛：责任人本人，或者质量条线。
     *
     * <p>管理员例外写在这里是有意的——厂里可能就一个人管质量，
     * 不给他例外的结果不是「更规范」，而是「这条措施永远没人动」。
     */
    private void requireOwnerOrQuality(Long id) {
        if (UserContext.canReview()) {
            return;
        }
        QcCapa capa = capaService.get(id);
        String me = UserContext.username();
        if (!me.equals(capa.getOwner()) && !me.equals(capa.getCreatedBy())) {
            throw BizException.of("这条措施是「" + capa.getOwner() + "」在跟，不是你。"
                    + "要接手请先让管理员改责任人——否则两个人各改各的，最后谁也说不清做完了没有");
        }
    }

    /** 空串当没填（前端清空日期框传的是空串，直接绑 LocalDate 会 400） */
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of("日期格式不对：" + s + "，要写成 2026-11-25 这样");
        }
    }

    // ------------------------------------------------------------------ 请求体

    public record CreateBody(String sourceType, Long sourceId, String sourceRef,
                             String title, String problem, Long productId, String defectCode,
                             String severity, String rootCause, String actionPlan, String preventPlan,
                             String owner, String dueDate, String remark) {
    }

    public record AnalysisBody(String rootCause, String actionPlan, String preventPlan) {
    }

    public record VerifyBody(Boolean effective, String note) {
    }

    public record CancelBody(String reason) {
    }
}
