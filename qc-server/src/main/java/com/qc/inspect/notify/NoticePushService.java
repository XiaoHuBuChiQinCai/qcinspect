package com.qc.inspect.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qc.inspect.entity.QcNotice;
import com.qc.inspect.entity.SysUser;
import com.qc.inspect.mapper.QcNoticeMapper;
import com.qc.inspect.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 把站内通知顺带发到企业微信。
 *
 * <p>整条链路的关键决定只有一条：<b>推送永远不在业务事务里做</b>。
 * 审核退回这件事必须快、必须成；企业微信那边可能超时、可能宕机、可能 IP 没进白名单。
 * 把两者绑在一起，结果是「企业微信抖一下，审核员点不动退回按钮」——
 * 用一个不重要的功能拖垮一个重要的功能，这是最典型的架构事故。
 *
 * <p>所以拆成三段：
 * <ol>
 *   <li>{@link #plan} 在插入前做本地判断（这个人绑了账号没有），零网络调用</li>
 *   <li>事务提交之后再派发事件（{@code AFTER_COMMIT}）——事务回滚了就一个字都不发</li>
 *   <li>异步线程里发 HTTP，失败只改 {@code push_state}，不抛回去</li>
 * </ol>
 *
 * <p>代价是「提交成功但进程正好挂了」会漏一条。这个取舍是明确的：
 * 站内通知才是权威记录，企业微信只是提醒；漏一条提醒，人下次进系统还看得到。
 * 反过来，为了不漏提醒而把发消息塞进事务、让退回操作取决于外网，才是真的错。
 */
@Service
public class NoticePushService {

    private static final Logger log = LoggerFactory.getLogger(NoticePushService.class);

    public static final String NONE = "NONE";
    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    /** 企业微信 text 消息正文上限 2048 字节，留点余量给标题与后缀 */
    private static final int MAX_BYTES = 2000;

    private final QcNoticeMapper noticeMapper;
    private final SysUserMapper userMapper;
    private final WeComClient client;
    private final WeComProperties props;
    private final ApplicationEventPublisher publisher;
    private final Executor executor;

    /**
     * 推送线程池。显式注入而不是用 {@code @Async}：
     * {@code @Async} 与 {@code @TransactionalEventListener} 叠在同一个方法上，
     * 到底走不走代理、走不走异步，取决于代理方式和调用路径，属于「看代码看不出来」的那类坑。
     * 自己 {@code execute} 一行，读代码的人一眼就知道它在别的线程里跑。
     *
     * <p>构造器手写而不是用 {@code @RequiredArgsConstructor}：
     * Lombok 默认不会把 {@code @Qualifier} 抄到构造参数上，
     * 而容器里本来就有 Boot 自动配置的 {@code applicationTaskExecutor}，
     * 少了这个限定符就变成「按类型找到两个 Executor」，启动直接失败。
     */
    public NoticePushService(QcNoticeMapper noticeMapper,
                             SysUserMapper userMapper,
                             WeComClient client,
                             WeComProperties props,
                             ApplicationEventPublisher publisher,
                             @Qualifier("noticePushExecutor") Executor executor) {
        this.noticeMapper = noticeMapper;
        this.userMapper = userMapper;
        this.client = client;
        this.props = props;
        this.publisher = publisher;
        this.executor = executor;
    }

    // ==================== 第一段：插入前定性 ====================

    /**
     * 决定这条通知要不要推、推给谁。必须在 {@code insert} 之前调用，
     * 这样状态是随 INSERT 一次写进去的，不会出现「先插一条 NONE 再 UPDATE 成 PENDING」的中间态。
     *
     * <p>通道关着时什么都不改：{@code push_state} 保持 NONE。
     * 不标 SKIPPED 是因为 SKIPPED 的意思是「该发但没人可发」，
     * 那会误导管理员去补账号——而真正的原因只是通道没开。
     */
    public void plan(QcNotice n) {
        n.setPushState(NONE);
        if (!client.isEnabled()) {
            return;
        }
        Target t = resolve(n.getReceiver());
        if (t.userId() == null) {
            n.setPushState(SKIPPED);
            n.setPushError(truncate(t.reason(), 500));
            return;
        }
        n.setPushState(PENDING);
        n.setPushTarget(t.userId());
    }

    /** 插入之后调用：只有真的要推的才上队列 */
    public void enqueue(QcNotice n) {
        if (PENDING.equals(n.getPushState()) && n.getId() != null) {
            publisher.publishEvent(new NoticeCreatedEvent(n.getId()));
        }
    }

    /**
     * 接收人展示名 → 企业微信 UserID。
     *
     * <p>按 {@code real_name} 与 {@code username} 双路匹配，因为 {@code receiver} 存的是
     * {@code displayName()}（有姓名用姓名，没有用账号）。同一个人可能两条都命中，
     * 按「有绑定优先、姓名精确优先」排序取第一条。
     */
    Target resolve(String receiver) {
        if (receiver == null || receiver.isBlank()) {
            return new Target(null, "接收人为空");
        }
        List<SysUser> candidates = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getEnabled, true)
                .and(w -> w.eq(SysUser::getRealName, receiver).or().eq(SysUser::getUsername, receiver))
                .orderByAsc(SysUser::getId));
        if (candidates.isEmpty()) {
            return new Target(null, "系统里没有这个账号：" + receiver);
        }
        SysUser hit = candidates.stream()
                .filter(u -> u.getWecomUserid() != null && !u.getWecomUserid().isBlank())
                .findFirst()
                .orElse(null);
        if (hit == null) {
            return new Target(null, "接收人未绑定企业微信账号：" + receiver);
        }
        if (candidates.stream().filter(u -> u.getWecomUserid() != null
                && !u.getWecomUserid().isBlank()).count() > 1) {
            // 重名 + 两个人都绑了账号：不猜。推给第一个并在日志里留痕，管理员看到就能改
            log.warn("接收人「{}」匹配到多个绑定了企业微信的账号，取 id={}（{}）",
                    receiver, hit.getId(), hit.getWecomUserid());
        }
        return new Target(hit.getWecomUserid().trim(), null);
    }

    // ==================== 第二段：提交后派发 ====================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNoticeCreated(NoticeCreatedEvent e) {
        Long id = e.noticeId();
        try {
            executor.execute(() -> deliver(id));
        } catch (RuntimeException ex) {
            // 线程池拒了（CallerRunsPolicy 下不会走到这，但别假设配置永远不变）
            log.warn("通知 {} 未能进入推送队列：{}", id, ex.getMessage());
            mark(id, FAILED, "推送队列拒绝：" + ex.getMessage());
        }
    }

    // ==================== 第三段：真的发 ====================

    /**
     * 发一条通知。同步方法，供异步线程与「手工重发」共同调用。
     *
     * <p>所有异常都在这里收口：推不出去是常态（人没绑、IP 没白名单），
     * 让它冒出去只会污染日志，没人能从堆栈里得到更多信息。
     */
    public void deliver(Long noticeId) {
        QcNotice n = noticeMapper.selectById(noticeId);
        if (n == null) {
            return;
        }
        if (!PENDING.equals(n.getPushState())) {
            // 已经发过 / 通道关了 / 被别人改过。重发接口会先把它置回 PENDING
            return;
        }
        String target = n.getPushTarget();
        if (target == null || target.isBlank()) {
            mark(noticeId, FAILED, "没有推送目标");
            return;
        }
        try {
            String msgId = client.sendText(target, buildText(n));
            mark(noticeId, SENT, null);
            log.info("已推送通知 {} 给 {}（msgid={}）", noticeId, target, msgId);
        } catch (WeComException e) {
            mark(noticeId, FAILED, e.getMessage());
            log.warn("通知 {} 推送失败：{}", noticeId, e.getMessage());
        } catch (RuntimeException e) {
            mark(noticeId, FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("通知 {} 推送异常", noticeId, e);
        }
    }

    /** 手工重发：管理员把账号补上之后，不必等下一次事件 */
    public boolean retry(Long noticeId) {
        if (!client.isEnabled()) {
            return false;
        }
        QcNotice n = noticeMapper.selectById(noticeId);
        if (n == null) {
            return false;
        }
        Target t = resolve(n.getReceiver());
        if (t.userId() == null) {
            mark(noticeId, SKIPPED, t.reason());
            return false;
        }
        noticeMapper.update(null, new LambdaUpdateWrapper<QcNotice>()
                .eq(QcNotice::getId, noticeId)
                .set(QcNotice::getPushState, PENDING)
                .set(QcNotice::getPushTarget, t.userId())
                .set(QcNotice::getPushError, null));
        executor.execute(() -> deliver(noticeId));
        return true;
    }

    private void mark(Long noticeId, String state, String error) {
        try {
            noticeMapper.update(null, new LambdaUpdateWrapper<QcNotice>()
                    .eq(QcNotice::getId, noticeId)
                    .set(QcNotice::getPushState, state)
                    .set(QcNotice::getPushAt, LocalDateTime.now())
                    .set(QcNotice::getPushError, truncate(error, 500)));
        } catch (RuntimeException e) {
            // 状态没写进去不影响「消息可能已经发出去了」这个事实，别让异常冒泡
            log.error("回写通知 {} 推送状态失败：{}", noticeId, e.getMessage());
        }
    }

    // ==================== 正文 ====================

    /**
     * 拼消息正文。
     *
     * <p>刻意做成纯文本而不是图文卡片：卡片要配 HTTPS 的跳转地址，
     * 而这个系统目前只有 IP 直连。给人一张点不开的卡片，不如给他一句话，
     * 外加一句「在哪看」——链接配了才带，没配就不带。
     */
    String buildText(QcNotice n) {
        return buildText(n, props.getAppUrl());
    }

    static String buildText(QcNotice n, String appUrl) {
        String head = "【工件质检】" + (n.getTitle() == null ? "新通知" : n.getTitle());
        StringBuilder body = new StringBuilder();
        if (n.getBizNo() != null && !n.getBizNo().isBlank()) {
            body.append("单号：").append(n.getBizNo()).append('\n');
        }
        if (n.getContent() != null && !n.getContent().isBlank()) {
            body.append(n.getContent().trim()).append('\n');
        }
        body.append("请在质检系统「通知」里查看处理。");
        String tail = appUrl == null || appUrl.isBlank() ? "" : "\n手机端：" + appUrl.trim();

        String content = head + "\n" + body + tail;
        return clamp(content, MAX_BYTES);
    }

    /** 按 UTF-8 字节截断，别把中文劈成半个 */
    static String clamp(String s, int maxBytes) {
        if (s == null) {
            return "";
        }
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        int bytes = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > maxBytes - 3) { // 留 3 字节给省略号
                break;
            }
            sb.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return sb.append("…").toString();
    }

    // ==================== 管理员视图 ====================

    /** 推送健康：开关、缺什么、各状态条数、最近没送到的几条 */
    public Map<String, Object> health(int recent) {
        int size = Math.min(Math.max(recent, 1), 50);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", client.isEnabled());
        m.put("missing", props.missingHint());

        Map<String, Long> states = new LinkedHashMap<>();
        for (String s : List.of(PENDING, SENT, FAILED, SKIPPED)) {
            states.put(s, noticeMapper.selectCount(new LambdaQueryWrapper<QcNotice>()
                    .eq(QcNotice::getPushState, s)));
        }
        m.put("states", states);

        List<QcNotice> problems = noticeMapper.selectList(new LambdaQueryWrapper<QcNotice>()
                .in(QcNotice::getPushState, PENDING, FAILED, SKIPPED)
                .orderByDesc(QcNotice::getId)
                .last("limit " + size));
        m.put("problems", problems);
        return m;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 解析结果：userId 为 null 时 reason 说明为什么没绑上 */
    record Target(String userId, String reason) {
    }
}
