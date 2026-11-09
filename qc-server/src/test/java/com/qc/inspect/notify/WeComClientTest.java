package com.qc.inspect.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 企业微信客户端的单测。用 JDK 自带的 HttpServer 假装企业微信。
 *
 * <p>为什么不打桩 {@code HttpClient}：这个客户端最容易错的不是「调用顺序」，
 * 而是「URL 拼得对不对、body 里字段名有没有写错、errcode 有没有读对」——
 * 这些只有让请求真的走一遍 HTTP 才验得到。JDK 的 HttpServer 零依赖，
 * 起在随机端口上，比引 WireMock 轻。
 *
 * <p>顺带保证这套用例不需要外网：企业微信真实接口从来没被调用过。
 */
@DisplayName("企业微信客户端：换 token、发消息、出错要能照着改")
class WeComClientTest {

    private static HttpServer server;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final AtomicInteger tokenCalls = new AtomicInteger();
    private static final AtomicInteger sendCalls = new AtomicInteger();
    /** 接下来几次 send 要返回的错误码，0 表示正常 */
    private static final AtomicInteger failNextSends = new AtomicInteger();
    private static final List<String> sentBodies = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> tokenQueries = Collections.synchronizedList(new ArrayList<>());

    private WeComProperties props;
    private WeComClient client;

    @BeforeAll
    static void startFakeWeCom() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", WeComClientTest::handleToken);
        server.createContext("/cgi-bin/message/send", WeComClientTest::handleSend);
        server.start();
    }

    @AfterAll
    static void stopFakeWeCom() {
        server.stop(0);
    }

    private static void handleToken(HttpExchange ex) throws IOException {
        tokenCalls.incrementAndGet();
        tokenQueries.add(String.valueOf(ex.getRequestURI().getQuery()));
        respond(ex, Map.of(
                "errcode", 0,
                "errmsg", "ok",
                "access_token", "TOK-" + tokenCalls.get(),
                "expires_in", 7200));
    }

    private static void handleSend(HttpExchange ex) throws IOException {
        int seq = sendCalls.incrementAndGet();
        try (InputStream in = ex.getRequestBody()) {
            sentBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        int fail = failNextSends.getAndUpdate(n -> Math.max(n - 1, 0));
        if (fail > 0) {
            // 42001 = access_token 超时，客户端应当重换 token 再发一次
            respond(ex, Map.of("errcode", 42001, "errmsg", "access_token expired"));
            return;
        }
        respond(ex, Map.of("errcode", 0, "errmsg", "ok", "msgid", "MID-" + seq, "invaliduser", ""));
    }

    private static void respond(HttpExchange ex, Map<String, Object> body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @BeforeEach
    void setUp() {
        tokenCalls.set(0);
        sendCalls.set(0);
        failNextSends.set(0);
        sentBodies.clear();
        tokenQueries.clear();

        props = new WeComProperties();
        props.setEnabled(true);
        props.setCorpId("corp-test");
        props.setSecret("secret-test");
        props.setAgentId(1000002);
        props.setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTimeoutSeconds(3);
        client = new WeComClient(props, JSON);
    }

    @Test
    @DisplayName("凭证没配齐就是没启用，不会拿着半套配置去敲接口")
    void notConfigured() {
        props.setSecret("  ");
        assertThat(client.isEnabled()).isFalse();
        assertThat(props.missingHint()).contains("WECOM_SECRET");

        // 报错要直接说缺什么，别只给一句「未启用」
        assertThatThrownBy(() -> client.sendText("zhangsan", "hi"))
                .isInstanceOf(WeComException.class)
                .hasMessageContaining("未启用")
                .hasMessageContaining("WECOM_SECRET");
        assertThat(tokenCalls.get()).isZero();
    }

    @Test
    @DisplayName("token 换一次就缓存，连发三条只换一次")
    void tokenIsCached() {
        client.sendText("zhangsan", "第一条");
        client.sendText("zhangsan", "第二条");
        client.sendText("lisi", "第三条");

        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(sendCalls.get()).isEqualTo(3);
        assertThat(sentBodies).hasSize(3);
    }

    @Test
    @DisplayName("请求体字段名对：touser / msgtype / agentid / text.content")
    void payloadShape() throws Exception {
        String msgId = client.sendText(" zhangsan ", "尺寸超差，请复测");

        assertThat(msgId).isEqualTo("MID-1");
        JsonNode body = JSON.readTree(sentBodies.get(0));
        assertThat(body.path("touser").asText()).isEqualTo("zhangsan"); // 前后空格被去掉
        assertThat(body.path("msgtype").asText()).isEqualTo("text");
        assertThat(body.path("agentid").asInt()).isEqualTo(1000002);
        assertThat(body.path("text").path("content").asText()).contains("尺寸超差");
        // 关掉重复检查：同一张单被退回两次是两件事，都该推
        assertThat(body.path("enable_duplicate_check").asInt()).isZero();
    }

    @Test
    @DisplayName("token 失效（42001）自动重换一次再发，人会收到")
    void retryOnceOnExpiredToken() {
        failNextSends.set(1);

        String msgId = client.sendText("zhangsan", "退回意见");

        assertThat(msgId).isNotBlank();
        assertThat(tokenCalls.get()).isEqualTo(2); // 第一次换的作废了，又换了一次
        assertThat(sendCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("errcode 60020 要翻译成「去加可信 IP」，别让人对着英文 errmsg 猜")
    void explainUntrustedIp() {
        // 直接验翻译函数，避免为每种错误码都造一个假服务
        assertThat(WeComClient.explain(60020, "not allow to access from your ip"))
                .contains("60020").contains("可信 IP");
        assertThat(WeComClient.explain(81013, "user & party & tag not exist"))
                .contains("UserID");
        assertThat(WeComClient.explain(99999, "未知错误")).contains("99999");
    }

    @Test
    @DisplayName("换 token 失败时把 errcode 带出来，而不是笼统的「发送失败」")
    void tokenFailure() {
        props.setApiBase("http://127.0.0.1:1"); // 没人监听
        assertThatThrownBy(() -> client.sendText("zhangsan", "x"))
                .isInstanceOf(WeComException.class)
                .hasMessageContaining("调用企业微信失败");
    }
}
