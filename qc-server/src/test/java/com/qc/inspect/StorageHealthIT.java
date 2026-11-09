package com.qc.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.mapper.SystemAlertMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：磁盘与容量。
 *
 * <p>这套用例验的不是「算术对不对」（那在 {@code StorageHealthTest} 里钉死了），
 * 而是三件只有接上真库真盘才会暴露的事：
 * <ol>
 *   <li>取数链路通不通——盘容量、库大小、照片统计、表体积排行，任何一个
 *       抛异常都会让整页变空白，而管理员看到空白只会以为「系统正常」</li>
 *   <li>照片统计的窗口过滤对不对：30 天前的照片要计入总量，但不能计入近期增量，
 *       否则「还能撑几天」全被历史数据拉平</li>
 *   <li>告警去重真的在库里生效：同一天调两次只能推一次</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("端到端：磁盘容量可见 + 告警去重")
class StorageHealthIT {

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
    @Autowired
    private SystemAlertMapper alertMapper;
    /** 直接连应用那个库（qcinspect），不是 EmbeddedPostgres 默认的 postgres 库 */
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Order(1)
    @DisplayName("管理员能看到盘、库、照片、表排行四块，缺一块都说明取数断了")
    void adminSeesEverything() {
        String token = login("admin", "admin123");
        JsonNode data = get("/api/system/storage-health", token).path("data");

        assertThat(data.path("level").asText()).isIn("OK", "WARN", "CRITICAL", "UNKNOWN");
        assertThat(data.path("flags").isArray()).isTrue();
        assertThat(data.path("flags")).isNotEmpty();

        // 监控的路径一定要露出来：容器挂错卷时，页面会显示「还有 40 GB」，
        // 而真实照片盘早满了——这种「看着正常」的错最难查
        assertThat(data.path("path").asText()).isNotBlank();
        assertThat(data.path("storageType").asText()).isEqualTo("local");

        assertThat(data.path("totalText").asText()).isNotBlank();
        assertThat(data.path("usableText").asText()).isNotBlank();
        assertThat(data.path("dbText").asText()).isNotBlank();

        assertThat(data.path("largestTables").isArray()).isTrue();
        assertThat(data.path("largestTables"))
                .as("库里一定有表，排行空着说明取数断了——而页面会把这一块默默留白")
                .isNotEmpty();
        JsonNode first = data.path("largestTables").get(0);
        assertThat(first.path("tableName").asText()).isNotBlank();
        assertThat(first.path("sizeText").asText()).isNotBlank();

        assertThat(data.path("thresholds").path("warnUsableText").asText()).isEqualTo("3.0 GB");
        assertThat(data.path("thresholds").path("criticalUsableText").asText()).isEqualTo("1.0 GB");
    }

    /**
     * 近期增量只算窗口内的。
     *
     * <p>如果窗口过滤写错、把三年前的照片也算进「最近 30 天」，
     * 算出来的增长速度会大得离谱，于是系统天天喊「磁盘要满了」——
     * 这种假警报持续一周，真警报来了就没人信了。
     */
    @Test
    @Order(2)
    @DisplayName("照片统计：总量含历史，近期增量只算窗口内")
    void imageWindow() {
        insertImage("old-photo.jpg", 10L * 1024 * 1024, "60 days");
        insertImage("new-photo.jpg", 2L * 1024 * 1024, "1 day");

        String token = login("admin", "admin123");
        JsonNode data = get("/api/system/storage-health", token).path("data");

        long count = data.path("imageCount").asLong();
        long bytes = data.path("imageBytes").asLong();
        assertThat(count).as("两张照片都该计入总数").isGreaterThanOrEqualTo(2);
        assertThat(bytes).as("两张都该计入总占用").isGreaterThanOrEqualTo(12L * 1024 * 1024);

        long recentBytes = data.path("recentImageBytes").asLong();
        assertThat(recentBytes).as("窗口内那张 2 MB 要算进来").isGreaterThanOrEqualTo(2L * 1024 * 1024);
        assertThat(recentBytes).as("60 天前那张 10 MB 不能算进来").isLessThan(bytes);

        // 两句展示文案也要对得上，不能只有数字对、页面显示错
        assertThat(data.path("recentImageText").asText()).isNotBlank();
        assertThat(data.path("imageText").asText()).isNotBlank();
    }

    @Test
    @Order(3)
    @DisplayName("同一天只推一次：第二次占用失败")
    void alertDedup() {
        String key = "STORAGE_IT_" + System.nanoTime();
        assertThat(alertMapper.claim(key, "WARN", "第一次")).isEqualTo(1);
        assertThat(alertMapper.claim(key, "WARN", "第二次")).isZero();
    }

    /**
     * 管理员之外的人看不到。
     *
     * <p>检验员看到「磁盘剩 800 MB」既不能删照片也不能扩盘，
     * 只会在心里多一条无法处理的事——这类灯必须亮在能关掉它的人面前。
     */
    @Test
    @Order(4)
    @DisplayName("只有管理员能看：检验员 403、未登录 401")
    void roleGuarded() {
        String admin = login("admin", "admin123");
        createInspector(admin, "it-storage-ins");
        String ins = login("it-storage-ins", "123456");

        assertThat(get("/api/system/storage-health", ins).path("code").asInt())
                .as("检验员不该看得到").isNotZero();

        ResponseEntity<String> anon = rest.getForEntity("/api/system/storage-health", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================== 辅助 ====================

    private void insertImage(String name, long size, String age) {
        jdbc.update("INSERT INTO qc_image (biz_type, biz_id, file_name, object_key, file_size, created_at) "
                        + "VALUES ('INSPECTION', 1, ?, ?, ?, now() - CAST(? AS interval))",
                name, "k/" + name, size, age);
    }

    private void createInspector(String token, String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("realName", "磁盘用例检验员");
        body.put("role", "INSPECTOR");
        body.put("password", "123456");
        body.put("enabled", true);
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/users",
                new HttpEntity<>(body, headers(token)), String.class);
        assertThat(read(resp.getBody()).path("code").asInt()).isZero();
    }

    private JsonNode get(String path, String token) {
        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class);
        return read(resp.getBody());
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private String login(String username, String password) {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers(null)),
                String.class);
        return read(resp.getBody()).path("data").path("token").asText();
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("返回体不是 JSON：" + body, e);
        }
    }
}
