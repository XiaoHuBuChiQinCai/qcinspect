package com.qc.inspect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.entity.QcNotice;
import com.qc.inspect.entity.SysUser;
import com.qc.inspect.mapper.QcNoticeMapper;
import com.qc.inspect.mapper.SysUserMapper;
import com.qc.inspect.notify.NoticePushService;
import com.qc.inspect.notify.WeComProperties;
import com.qc.inspect.service.NoticeService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：站内通知写进库之后，顺带推到企业微信。
 *
 * <p>这套用例真正在验的不是「HTTP 会不会发」，而是三条容易做错的边界：
 * <ol>
 *   <li>没绑账号的人是 SKIPPED 而不是 FAILED —— 前者要管理员去补映射，后者要去查凭证，
 *       混成一个值，管理员就只能对着一条错误说「不知道找谁」</li>
 *   <li>通道关着时状态必须是 NONE，不能标成 SKIPPED —— 否则一开通道会看到一堆
 *       「未绑定」的假故障</li>
 *   <li>推送失败绝不影响站内通知本身：通知照旧未读、照旧能在列表里看到</li>
 * </ol>
 *
 * <p>企业微信用 JDK 的 HttpServer 假装，整个用例不需要外网。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("端到端：通知写库 → 推到企业微信 → 失败可重发")
class NoticePushIT {

    private static EmbeddedPostgres postgres;
    private static HttpServer fakeWeCom;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final AtomicInteger tokenCalls = new AtomicInteger();
    private static final AtomicInteger sendCalls = new AtomicInteger();
    private static final AtomicInteger failNextSends = new AtomicInteger();
    private static volatile int failCode = 60020;
    private static final List<String> sentBodies = Collections.synchronizedList(new ArrayList<>());

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (postgres == null) {
            postgres = EmbeddedPostgres.builder().start();
            try (Connection conn = postgres.getPostgresDatabase().getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE qcinspect");
            }
        }
        registry.add("spring.datasource.url", () -> {
            String base = postgres.getJdbcUrl("postgres", "qcinspect");
            return base + (base.contains("?") ? "&" : "?") + "stringtype=unspecified";
        });
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeAll
    static void startFakeWeCom() throws IOException {
        fakeWeCom = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeWeCom.createContext("/cgi-bin/gettoken", ex -> {
            tokenCalls.incrementAndGet();
            respond(ex, Map.of("errcode", 0, "errmsg", "ok",
                    "access_token", "TOK-" + tokenCalls.get(), "expires_in", 7200));
        });
        fakeWeCom.createContext("/cgi-bin/message/send", ex -> {
            int seq = sendCalls.incrementAndGet();
            try (InputStream in = ex.getRequestBody()) {
                sentBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            int fail = failNextSends.getAndUpdate(n -> Math.max(n - 1, 0));
            if (fail > 0) {
                respond(ex, Map.of("errcode", failCode, "errmsg", "not allow to access from your ip"));
            } else {
                respond(ex, Map.of("errcode", 0, "errmsg", "ok", "msgid", "MID-" + seq, "invaliduser", ""));
            }
        });
        fakeWeCom.start();
    }

    private static void respond(HttpExchange ex, Map<String, Object> body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterAll
    static void stopFakeWeCom() {
        if (fakeWeCom != null) {
            fakeWeCom.stop(0);
        }
    }

    @Autowired
    private NoticeService noticeService;
    @Autowired
    private NoticePushService pushService;
    @Autowired
    private WeComProperties props;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private QcNoticeMapper noticeMapper;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void armChannel() {
        tokenCalls.set(0);
        sendCalls.set(0);
        failNextSends.set(0);
        failCode = 60020;
        sentBodies.clear();
        props.setEnabled(true);
        props.setCorpId("corp-it");
        props.setSecret("secret-it");
        props.setAgentId(1000002);
        props.setApiBase("http://127.0.0.1:" + fakeWeCom.getAddress().getPort());
        props.setTimeoutSeconds(3);
        props.setAppUrl(null);
    }

    // ==================== 用例 ====================

    @Test
    @Order(1)
    @DisplayName("绑了账号的人会收到：正文带单号，末尾带手机端入口")
    void delivered() throws Exception {
        user("p-zhangsan", "张三", "wx-zhangsan");
        props.setAppUrl("http://154.8.209.29/h5/");

        noticeService.push("张三", NoticeService.TYPE_REVIEW_REJECT,
                "审核退回：QC-2026-0001", "尺寸超差，请复测后重新提交",
                "INSPECTION", 1L, "QC-2026-0001");

        QcNotice n = awaitState("张三", NoticePushService.SENT);
        assertThat(n.getPushState()).isEqualTo(NoticePushService.SENT);
        assertThat(n.getPushTarget()).isEqualTo("wx-zhangsan");
        assertThat(n.getPushError()).isNull();
        assertThat(n.getPushAt()).isNotNull();
        // 站内那一条该有的东西一样不少
        assertThat(n.getReadFlag()).isZero();
        assertThat(n.getBizNo()).isEqualTo("QC-2026-0001");

        assertThat(sentBodies).hasSize(1);
        String body = sentBodies.get(0);
        assertThat(body).contains("审核退回").contains("单号：QC-2026-0001")
                .contains("尺寸超差，请复测后重新提交")
                .contains("手机端：http://154.8.209.29/h5/");
        // 发给了谁，请求体里必须写死这件事，不能靠"应该是对的"
        assertThat(JSON.readTree(body).path("touser").asText()).isEqualTo("wx-zhangsan");
    }

    @Test
    @Order(2)
    @DisplayName("没绑账号的人标 SKIPPED，一个请求都不发，站内通知照旧")
    void skippedWhenNotBound() throws Exception {
        user("p-lisi", "李四", null);

        noticeService.push("李四", NoticeService.TYPE_REVIEW_REJECT,
                "审核退回：QC-2026-0002", "外径超差", "INSPECTION", 2L, "QC-2026-0002");

        QcNotice n = awaitState("李四", NoticePushService.SKIPPED);
        assertThat(n.getPushState()).isEqualTo(NoticePushService.SKIPPED);
        assertThat(n.getPushError()).contains("未绑定企业微信");
        assertThat(n.getPushAt()).isNull();
        assertThat(sendCalls.get()).isZero();
        assertThat(tokenCalls.get()).isZero(); // 连 token 都不去换，别浪费一次网络往返
        assertThat(n.getReadFlag()).isZero();  // 站内通知照旧未读，推送是增量不是替代
    }

    @Test
    @Order(3)
    @DisplayName("通道关着时状态是 NONE，不是 SKIPPED —— 否则一开通道满屏假故障")
    void disabledKeepsNone() throws Exception {
        user("p-wangwu", "王五", "wx-wangwu");
        props.setEnabled(false);

        noticeService.push("王五", NoticeService.TYPE_CONCESSION,
                "让步接收待审批：QC-2026-0003", "客户同意特采", "DEFECT", 3L, "QC-2026-0003");

        QcNotice n = latest("王五");
        assertThat(n.getPushState()).isEqualTo(NoticePushService.NONE);
        assertThat(n.getPushError()).isNull();
        assertThat(sendCalls.get()).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("企业微信报错就是 FAILED，errcode 与可操作提示都要留住")
    void failedKeepsErrcode() throws Exception {
        user("p-zhaoliu", "赵六", "wx-zhaoliu");
        failNextSends.set(1);
        failCode = 60020;

        noticeService.push("赵六", NoticeService.TYPE_REVIEW_REJECT,
                "审核退回：QC-2026-0004", "长度超差", "INSPECTION", 4L, "QC-2026-0004");

        QcNotice n = awaitState("赵六", NoticePushService.FAILED);
        assertThat(n.getPushState()).isEqualTo(NoticePushService.FAILED);
        assertThat(n.getPushError()).contains("60020").contains("可信 IP");
        assertThat(n.getReadFlag()).isZero(); // 推不出去不影响站内这条记录
    }

    @Test
    @Order(5)
    @DisplayName("补上账号后手工重发能补发成功")
    void retryAfterBinding() throws Exception {
        SysUser u = user("p-sunqi", "孙七", null);
        noticeService.push("孙七", NoticeService.TYPE_REVIEW_REJECT,
                "审核退回：QC-2026-0005", "缺测点", "INSPECTION", 5L, "QC-2026-0005");
        QcNotice n = awaitState("孙七", NoticePushService.SKIPPED);

        // 管理员在用户管理里把企业微信账号填上
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, u.getId())
                .set(SysUser::getWecomUserid, "wx-sunqi"));

        assertThat(pushService.retry(n.getId())).isTrue();
        QcNotice after = awaitId(n.getId(), NoticePushService.SENT);
        assertThat(after.getPushState()).isEqualTo(NoticePushService.SENT);
        assertThat(after.getPushTarget()).isEqualTo("wx-sunqi");
        assertThat(after.getPushError()).isNull();
        assertThat(sentBodies).hasSize(1);
    }

    @Test
    @Order(6)
    @DisplayName("一个企业微信账号不能绑两个人，否则一条退回会发两遍")
    void wecomIdMustBeUnique() {
        String admin = login("admin", "admin123");
        assertThat(createUser(admin, "p-a", "甲", "dup-wx").get("code").asInt()).isZero();

        JsonNode dup = createUser(admin, "p-b", "乙", "dup-wx");
        assertThat(dup.path("code").asInt()).isNotZero();
        assertThat(dup.path("msg").asText()).contains("已绑定");
    }

    @Test
    @Order(7)
    @DisplayName("推送健康只给管理员：未登录 401，检验员 403")
    void healthIsAdminOnly() {
        assertThat(rest.getForEntity("/api/system/notice-push", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String admin = login("admin", "admin123");
        createUser(admin, "p-insp", "检验员甲", null);
        String insp = login("p-insp", "123456");
        ResponseEntity<String> forbidden = rest.exchange("/api/system/notice-push",
                HttpMethod.GET, new HttpEntity<>(headers(insp)), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> ok = rest.exchange("/api/system/notice-push",
                HttpMethod.GET, new HttpEntity<>(headers(admin)), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = read(ok.getBody()).path("data");
        assertThat(data.path("enabled").asBoolean()).isTrue();
        // 前面几条用例造的失败要能被管理员看见，否则这个接口等于没做
        assertThat(data.path("states").path("SKIPPED").asLong()).isGreaterThan(0);
        assertThat(data.path("problems").isArray()).isTrue();
    }

    // ==================== 工具 ====================

    private SysUser user(String username, String realName, String wecomUserid) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setPassword("x"); // 走接口登录的账号会另建，这里只关心接收人解析
        u.setRole("INSPECTOR");
        u.setEnabled(true);
        u.setDelFlag(0);
        u.setWecomUserid(wecomUserid);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(u);
        return u;
    }

    private QcNotice latest(String receiver) {
        return noticeMapper.selectOne(new LambdaQueryWrapper<QcNotice>()
                .eq(QcNotice::getReceiver, receiver)
                .orderByDesc(QcNotice::getId)
                .last("limit 1"));
    }

    /** 推送在异步线程里跑，等它落到终态；超时就把当前状态返回，让断言给出真实原因 */
    private QcNotice awaitState(String receiver, String wanted) throws InterruptedException {
        QcNotice n = null;
        for (int i = 0; i < 100; i++) {
            n = latest(receiver);
            if (n != null && wanted.equals(n.getPushState())) {
                return n;
            }
            Thread.sleep(50);
        }
        return n == null ? new QcNotice() : n;
    }

    private QcNotice awaitId(Long id, String wanted) throws InterruptedException {
        QcNotice n = null;
        for (int i = 0; i < 100; i++) {
            n = noticeMapper.selectById(id);
            if (n != null && wanted.equals(n.getPushState())) {
                return n;
            }
            Thread.sleep(50);
        }
        return n == null ? new QcNotice() : n;
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private String login(String username, String password) {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers(null)),
                String.class);
        return read(resp.getBody()).path("data").path("token").asText();
    }

    private Map<String, Object> newUser(String username, String realName, String wecomUserid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("realName", realName);
        body.put("role", "INSPECTOR");
        body.put("password", "123456");
        body.put("enabled", true);
        body.put("wecomUserid", wecomUserid);
        return body;
    }

    private JsonNode createUser(String token, String username, String realName, String wecomUserid) {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/users",
                new HttpEntity<>(newUser(username, realName, wecomUserid), headers(token)), String.class);
        return read(resp.getBody());
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("返回体不是 JSON：" + body, e);
        }
    }
}
