package com.qc.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：真实 PostgreSQL + 真实 HTTP + 真实 JWT。
 *
 * <p>这套用例存在的理由：之前所有验证都是「能编译 + 判定引擎单测」，而
 * 建表脚本、MyBatis 映射、权限注解、事务边界这四样恰恰是单测覆盖不到的，
 * 它们出错的表现形式是「启动正常、一调接口就炸」。
 *
 * <p>为什么不引 H2 省事：本项目用了 jsonb、timestamptz、带 WHERE 的部分唯一索引
 * 和窗口函数，H2 的 PostgreSQL 兼容模式跑不过，就算跑过了也不能证明生产能跑。
 * 宁可多下一个几十兆的 PostgreSQL 二进制包。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("端到端：建单 → 填报 → 判定 → 审核 → 导出")
class InspectionFlowIT {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (postgres == null) {
            postgres = EmbeddedPostgres.builder().start();
            try (Connection conn = postgres.getPostgresDatabase().getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE qcinspect");
            }
        }
        // Zonky 返回的 URL 自带 ?user=postgres，必须用 & 追加参数，
        // 否则整串会被当成用户名（报「角色 "postgres?stringtype=..." 不存在」）
        registry.add("spring.datasource.url", () -> {
            String base = postgres.getJdbcUrl("postgres", "qcinspect");
            return base + (base.contains("?") ? "&" : "?") + "stringtype=unspecified";
        });
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    private static String token;
    private static Long batchId;
    private static Long firstInspectionId;

    // ==================== 用例 ====================

    @Test
    @Order(1)
    @DisplayName("管理员登录拿到 JWT，未带 token 的请求被拦")
    void login() {
        // 未登录一律 401：这是「所有接口默认关闭」的底线，不能因为开发期方便就放开
        assertThat(rest.getForEntity("/api/batches", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> data = data(post("/api/auth/login",
                Map.of("username", "admin", "password", "admin123")));
        token = (String) data.get("token");
        assertThat(token).isNotBlank();

        Map<String, Object> user = asMap(data.get("user"));
        assertThat(user.get("role")).isEqualTo("ADMIN");
    }

    @Test
    @Order(2)
    @DisplayName("建单：按标准快照展开成 件 × 项，明细自带限值")
    void createInspection() {
        List<Map<String, Object>> batches = asList(get("/api/batches?status=PENDING").get("data"));
        assertThat(batches).as("V1 脚本应自带一条待检批次").isNotEmpty();

        batchId = num(batches.get(0).get("id"));
        Map<String, Object> created = data(post("/api/inspections",
                Map.of("batchId", batchId, "sampleQty", 2)));
        firstInspectionId = num(created.get("inspectionId"));
        assertThat(firstInspectionId).isNotNull();

        Map<String, Object> detail = detail(firstInspectionId);
        Map<String, Object> ins = asMap(detail.get("inspection"));
        assertThat(ins.get("status")).isEqualTo("DRAFT");
        assertThat(asList(detail.get("pieces"))).hasSize(2);

        List<Map<String, Object>> rows = rows(detail);
        assertThat(rows).isNotEmpty();
        // 快照必须落库：限值、类型、合格值都在明细行上，标准后续改版不影响本单
        for (Map<String, Object> row : rows) {
            assertThat(row.get("valueType")).isNotNull();
            assertThat(row.get("itemName")).isNotNull();
        }
    }

    @Test
    @Order(3)
    @DisplayName("提交：填合规实测值判合格，单据立即锁定")
    void submitPasses() {
        Map<String, Object> detail = detail(firstInspectionId);
        Map<String, Object> outcome = data(post("/api/inspections/" + firstInspectionId + "/submit",
                Map.of("lines", fillCompliant(detail))));

        assertThat(outcome.get("pass")).as("全部按限值中值填报，应判合格").isEqualTo(true);
        assertThat(num(outcome.get("failedQty"))).isZero();
        assertThat(num(outcome.get("passedQty"))).isEqualTo(2);

        Map<String, Object> after = detail(firstInspectionId);
        assertThat(asMap(after.get("inspection")).get("status")).isEqualTo("SUBMITTED");
        assertThat(after.get("locked")).as("提交后前端必须只读").isEqualTo(true);
    }

    @Test
    @Order(4)
    @DisplayName("已提交的单不能再提交，也不能原地改值")
    void submittedIsLocked() {
        Map<String, Object> again = post("/api/inspections/" + firstInspectionId + "/submit",
                Map.of("lines", List.of()));
        assertThat(again.get("code")).as("二次提交必须被拒绝").isNotEqualTo(0);
    }

    @Test
    @Order(5)
    @DisplayName("审核通过：归档、记审核痕迹、批次转已完成、待办清零")
    void reviewApproves() {
        assertThat(num(post("/api/inspections/" + firstInspectionId + "/review",
                Map.of("approve", true, "comment", "抽检合格，准予放行")).get("code"))).isZero();

        Map<String, Object> ins = asMap(detail(firstInspectionId).get("inspection"));
        assertThat(ins.get("status")).isEqualTo("CLOSED");
        assertThat(ins.get("reviewedBy")).as("审核痕迹是质量记录的证据").isNotNull();
        assertThat(ins.get("reviewedAt")).isNotNull();

        List<Map<String, Object>> batches = asList(get("/api/batches").get("data"));
        Map<String, Object> batch = batches.stream()
                .filter(b -> batchId.equals(num(b.get("id")))).findFirst().orElseThrow();
        assertThat(batch.get("status")).as("审核通过后才算批次完成").isEqualTo("DONE");

        Map<String, Object> summary = data(get("/api/todo/summary"));
        assertThat(num(summary.get("pendingReview"))).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("导出：检验单台账带 UTF-8 BOM 与表头")
    void exportLedger() {
        ResponseEntity<byte[]> resp = rest.exchange("/api/export/inspections", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        byte[] body = resp.getBody();
        assertThat(body).isNotNull();
        // 没有这三个字节，Excel 双击打开就是一堆乱码，这是导出功能能不能用的分水岭
        assertThat(new byte[]{body[0], body[1], body[2]})
                .isEqualTo(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("检验单号")
                .contains("批次号")
                .contains("判定结论");
    }

    @Test
    @Order(7)
    @DisplayName("审核退回：回到草稿、必须写意见、留痕、批次不算完成")
    void reviewRejects() {
        Map<String, Object> created = data(post("/api/inspections",
                Map.of("batchId", batchId, "sampleQty", 1)));
        Long id = num(created.get("inspectionId"));

        data(post("/api/inspections/" + id + "/submit",
                Map.of("lines", fillCompliant(detail(id)))));

        // 不写意见必须被拦住，否则检验员拿着一张空白退回单不知道改什么
        Map<String, Object> emptyComment = post("/api/inspections/" + id + "/review",
                Map.of("approve", false, "comment", "  "));
        assertThat(emptyComment.get("code")).isNotEqualTo(0);

        Map<String, Object> ok = post("/api/inspections/" + id + "/review",
                Map.of("approve", false, "comment", "第 1 件外观项描述过于笼统，请补照片"));
        assertThat(num(ok.get("code"))).isZero();

        Map<String, Object> ins = asMap(detail(id).get("inspection"));
        assertThat(ins.get("status")).isEqualTo("DRAFT");
        assertThat(String.valueOf(ins.get("judgeReason"))).contains("审核退回");
        // 退回也要记下是谁退的、为什么退——「谁审的」和「谁批的」是两件事，
        // 但都不该是空白，否则复审时无人可问
        assertThat(ins.get("reviewedBy")).isNotNull();
        assertThat(String.valueOf(ins.get("reviewComment"))).contains("照片");
    }

    @Test
    @Order(8)
    @DisplayName("审核退回主动通知录入人，通知只能本人读")
    void rejectNotifiesInspector() {
        // 换一个真人来录单：管理员自审自退时接收人就是操作人，通知会被刻意跳过，
        // 这条用例要验的是「别人退给我的单，我能收到」
        data(post("/api/auth/users", Map.of(
                "username", "it_inspector",
                "realName", "集成检验员",
                "role", "INSPECTOR",
                "enabled", true,
                "password", "it123456")));

        String adminToken = token;
        String inspectorToken = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        assertThat(inspectorToken).isNotBlank();

        Long id;
        token = inspectorToken;
        try {
            id = num(data(post("/api/inspections", Map.of("batchId", batchId, "sampleQty", 1)))
                    .get("inspectionId"));
            data(post("/api/inspections/" + id + "/submit",
                    Map.of("lines", fillCompliant(detail(id)))));
        } finally {
            token = adminToken;
        }

        data(post("/api/inspections/" + id + "/review",
                Map.of("approve", false, "comment", "第 1 件实测值请附量具编号，否则无法证明数据有效")));

        Long noticeId = null;
        token = inspectorToken;
        try {
            // 退回之后录入人一进 App 就应当看到角标，不必自己去翻待办列表
            assertThat(num(data(get("/api/notices/summary")).get("unread")))
                    .as("退回必须产生一条未读通知").isEqualTo(1);

            List<Map<String, Object>> list = asList(get("/api/notices?limit=20").get("data"));
            assertThat(list).hasSize(1);

            Map<String, Object> n = list.get(0);
            noticeId = num(n.get("id"));
            assertThat(n.get("noticeType")).isEqualTo("REVIEW_REJECT");
            assertThat(num(n.get("bizId"))).as("通知要能点回原单").isEqualTo(id);
            assertThat(String.valueOf(n.get("title"))).contains("退回");
            assertThat(String.valueOf(n.get("content"))).contains("量具编号");

            assertThat(num(post("/api/notices/" + noticeId + "/read", Map.of()).get("code"))).isZero();
            assertThat(num(data(get("/api/notices/summary")).get("unread"))).as("读过的通知不该再计数").isZero();
        } finally {
            token = adminToken;
        }

        // 通知归属按登录态判定：别人的通知对他而言就是不存在
        assertThat(num(post("/api/notices/" + noticeId + "/read", Map.of()).get("code")))
                .as("不能读别人的通知").isNotEqualTo(0);
    }

    // ==================== 填报辅助 ====================

    /** 按每行的限值/合格值方向造一套必然合格的实测值 */
    private List<Map<String, Object>> fillCompliant(Map<String, Object> detail) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> row : rows(detail)) {
            String type = String.valueOf(row.get("valueType"));
            int points = row.get("pointCount") == null ? 1
                    : Math.max(1, ((Number) row.get("pointCount")).intValue());

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", num(row.get("id")));

            switch (type) {
                case "NUMBER" -> {
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < points; i++) {
                        values.add(midValue(row).toPlainString());
                    }
                    line.put("values", values);
                }
                case "BOOL" -> {
                    // 合格值方向由 bool_pass_value 决定，配反了整批判反，这里必须跟着走
                    boolean passTrue = !Boolean.FALSE.equals(row.get("boolPassValue"));
                    line.put("values", List.of(passTrue ? "true" : "false"));
                }
                case "ENUM" -> line.put("values", List.of(passOption(row)));
                default -> line.put("textValue", "外观检查无异常");
            }
            lines.add(line);
        }
        return lines;
    }

    private BigDecimal midValue(Map<String, Object> row) {
        BigDecimal min = dec(row.get("minValue"));
        BigDecimal max = dec(row.get("maxValue"));
        if (min != null && max != null) {
            return min.add(max).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        }
        if (min != null) {
            return min;
        }
        if (max != null) {
            return max;
        }
        // 没有限值的数值项（纯记录用），给一个不越界的定点值
        return BigDecimal.ONE;
    }

    private String passOption(Map<String, Object> row) {
        Object raw = row.get("optionsJson");
        if (raw == null) {
            return "";
        }
        try {
            JsonNode arr = json.readTree(String.valueOf(raw));
            for (JsonNode node : arr) {
                if (node.path("pass").asBoolean(false)) {
                    return node.path("code").asText();
                }
            }
            return arr.isEmpty() ? "" : arr.get(0).path("code").asText();
        } catch (IOException e) {
            return "";
        }
    }

    // ==================== HTTP 辅助 ====================

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> resp = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), Map.class);
        return resp.getBody() == null ? Map.of() : resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        ResponseEntity<Map> resp = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()), Map.class);
        return resp.getBody() == null ? Map.of() : resp.getBody();
    }

    /** 取 R.data，并要求 code=0 —— 免得某个接口悄悄返回错误还被当成通过 */
    private Map<String, Object> data(Map<String, Object> r) {
        assertThat(num(r.get("code"))).as("接口返回失败: %s", r.get("msg")).isZero();
        return asMap(r.get("data"));
    }

    private Map<String, Object> detail(Long id) {
        return data(get("/api/inspections/" + id));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> detail) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> piece : asList(detail.get("pieces"))) {
            rows.addAll(asList(piece.get("rows")));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    private Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }
}
