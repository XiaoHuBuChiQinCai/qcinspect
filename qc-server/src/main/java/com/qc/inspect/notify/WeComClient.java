package com.qc.inspect.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 企业微信「应用消息」的最小客户端。
 *
 * <p>只做两件事：换 access_token、发一条 text。不用官方 SDK ——
 * 两个 HTTP 请求不值得引一个几十兆、还得跟着版本升级的依赖，
 * 尤其本项目在 2G 机器上跑，能不加的东西就不加。
 *
 * <p>三个必须做对的地方：
 * <ul>
 *   <li><b>token 缓存</b>：有效期 7200 秒，每次发消息都重新换一次会撞上企业微信的
 *       调用频次限制（gettoken 有明显配额）。这里提前 5 分钟过期，
 *       避免「刚换到手的 token 在路上就过期了」</li>
 *   <li><b>token 失效重试一次</b>：服务重启、后台重置 Secret 都会让手里的 token
 *       突然作废（40014 / 42001 / 42007 / 41001）。这类错误重换一次就好，
 *       重试一次是划算的；但只重试一次，不然配错 Secret 会变成死循环</li>
 *   <li><b>超时</b>：默认 5 秒。推送跑在业务提交之后的异步线程里，
 *       一个不设超时的 HTTP 调用足以把整个推送队列拖死</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WeComClient {

    private static final Logger log = LoggerFactory.getLogger(WeComClient.class);

    /** token 相关的错误码：重换一次 token 再试 */
    private static final Set<Integer> TOKEN_ERRORS = Set.of(40014, 42001, 42007, 41001);

    private final WeComProperties props;
    private final ObjectMapper objectMapper;

    private volatile String token;
    private volatile long tokenExpireAt;

    private HttpClient http;

    /** 通道是否可用：开关打开且三样凭证齐全 */
    public boolean isEnabled() {
        return props.isEnabled() && props.configured();
    }

    /**
     * 发一条文本消息。
     *
     * @param userId  企业微信 UserID
     * @param content 正文，调用方负责长度（企业微信 text 上限 2048 字节）
     * @return 企业微信返回的 msgid，仅用于日志对账
     * @throws WeComException 任何失败；网络异常也包成它，调用方只需 catch 一种
     */
    public String sendText(String userId, String content) {
        if (!isEnabled()) {
            throw new WeComException(-1, "企业微信通道未启用：" + props.missingHint());
        }
        if (userId == null || userId.isBlank()) {
            throw new WeComException(-1, "接收人的企业微信账号为空");
        }
        try {
            return doSend(userId.trim(), content);
        } catch (WeComException e) {
            if (TOKEN_ERRORS.contains(e.getErrCode())) {
                log.info("企业微信 token 失效（errcode={}），重换一次再发", e.getErrCode());
                invalidateToken();
                return doSend(userId.trim(), content);
            }
            throw e;
        }
    }

    private String doSend(String userId, String content) {
        // 每发必确认手里有 token：缓存命中时不走网络，缓存失效（或被上面的重试清掉）时现换
        String accessToken = token();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", userId);
        body.put("msgtype", "text");
        body.put("agentid", props.getAgentId());
        body.put("text", Map.of("content", content));
        // 关闭重复消息检查：同一张单被退回两次是两次独立事件，都该推
        body.put("enable_duplicate_check", 0);

        String url = props.getApiBase() + "/cgi-bin/message/send?access_token=" + enc(accessToken);
        JsonNode node = post(url, body);

        int errCode = node.path("errcode").asInt(0);
        if (errCode != 0) {
            throw new WeComException(errCode, explain(errCode, node.path("errmsg").asText("")));
        }
        // errcode=0 但 invaliduser 非空：账号存在写法问题，消息其实没人收
        String invalid = node.path("invaliduser").asText("");
        if (!invalid.isBlank()) {
            throw new WeComException(-1, "企业微信不认识这个账号：" + invalid
                    + "（UserID 不是手机号，也不是姓名）");
        }
        return node.path("msgid").asText("");
    }

    /** 换 token，带缓存 */
    private String token() {
        String t = token;
        if (t != null && System.currentTimeMillis() < tokenExpireAt) {
            return t;
        }
        synchronized (this) {
            if (token != null && System.currentTimeMillis() < tokenExpireAt) {
                return token;
            }
            String url = props.getApiBase() + "/cgi-bin/gettoken?corpid=" + enc(props.getCorpId())
                    + "&corpsecret=" + enc(props.getSecret());
            JsonNode node = get(url);
            int errCode = node.path("errcode").asInt(0);
            if (errCode != 0) {
                throw new WeComException(errCode,
                        "换取 access_token 失败：" + explain(errCode, node.path("errmsg").asText("")));
            }
            String fresh = node.path("access_token").asText("");
            if (fresh.isBlank()) {
                throw new WeComException(-1, "换取 access_token 失败：返回体里没有 access_token");
            }
            int expiresIn = node.path("expires_in").asInt(7200);
            token = fresh;
            // 提前 5 分钟过期，别让手里的 token 在路上失效
            tokenExpireAt = System.currentTimeMillis() + Math.max(expiresIn - 300, 60) * 1000L;
            log.debug("已刷新企业微信 access_token，有效期 {} 秒", expiresIn);
            return fresh;
        }
    }

    private void invalidateToken() {
        token = null;
        tokenExpireAt = 0;
    }

    private JsonNode get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .GET()
                .build();
        return execute(req);
    }

    private JsonNode post(String url, Object body) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new WeComException("拼装请求体失败：" + e.getMessage(), e);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return execute(req);
    }

    private JsonNode execute(HttpRequest req) {
        try {
            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new WeComException(-1, "企业微信返回 HTTP " + resp.statusCode()
                        + "（检查服务器出网与 " + props.getApiBase() + " 是否可达）");
            }
            return objectMapper.readTree(resp.body());
        } catch (IOException e) {
            // 连不上是内网部署最常见的情况：出口被防火墙拦了，或者没配 DNS
            throw new WeComException("调用企业微信失败：" + e.getClass().getSimpleName()
                    + " " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeComException("调用企业微信被中断", e);
        }
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(Math.min(props.getTimeoutSeconds(), 3)))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
                c = http;
            }
        }
        return c;
    }

    /**
     * 把 errcode 翻成人能照着做的下一步。
     *
     * <p>企业微信的 errmsg 是英文短句，现场没人愿意去查表；
     * 这里只翻「配错了」这一类——它们占实际故障的九成，且解法是确定的。
     * 其余原样带出 errcode，至少能拿去搜。
     */
    static String explain(int errCode, String errMsg) {
        String hint = switch (errCode) {
            case 60020 -> "服务器 IP 不在应用的可信 IP 列表里，去企业微信后台把出口 IP 加进去";
            case 40013 -> "企业 ID（WECOM_CORP_ID）不对";
            case 40001, 40091 -> "应用 Secret（WECOM_SECRET）不对，可能已在后台重置";
            case 40056 -> "AgentId 不合法，检查 WECOM_AGENT_ID";
            case 81013, 60111, 40003 -> "企业微信里没有这个账号，或此人不在应用可见范围内，检查 UserID";
            case 301002 -> "这个应用没有发消息的权限";
            case 45009 -> "接口调用超过当日限额";
            default -> "";
        };
        return "errcode=" + errCode + " " + errMsg + (hint.isEmpty() ? "" : "（" + hint + "）");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
