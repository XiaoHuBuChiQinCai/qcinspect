package com.qc.inspect;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.auth.LoginLock;
import com.qc.inspect.entity.SysUser;
import com.qc.inspect.mapper.SysUserMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：连续输错密码会锁账号，锁着的时候密码对了也不放行。
 *
 * <p>这套用例真正在验的不是「计数到 5 会不会拦」，而是四条容易做反的边界：
 * <ol>
 *   <li><b>锁着的时候必须先拒、再验密码</b>。先验密码再判锁，等于锁了个寂寞——
 *       密码一旦被猜对，那一刻就是它唯一派上用场的时候</li>
 *   <li><b>成功要清零</b>。不清零的话，「错 4 次 → 成功 → 再错 1 次」会立刻被锁，
 *       而这个人刚才明明登进来过</li>
 *   <li><b>锁定同时把计数清零</b>。留着 5，期满后只要再错一次就立刻重新锁定，号码等于永久废掉</li>
 *   <li><b>锁的是这个账号，不是这台机器</b>。别人不受影响——厂内几十台设备走同一个出口，
 *       锁 IP 会把整条产线一起锁掉</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@DisplayName("端到端：连续密码错误 → 锁定 → 管理员解锁")
class LoginLockIT {

    private static EmbeddedPostgres postgres;
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private SysUserMapper userMapper;

    // ==================== 用例 ====================

    @Test
    @DisplayName("第 5 次才锁；锁上之后密码对了也不放行，且提示说清等多久")
    void locksOnFifthAndThenRefusesEvenCorrectPassword() throws Exception {
        Long id = user("lock-a", "锁甲", "LOCKER-A");
        String admin = adminToken();

        // 前 4 次都是普通的「账号或密码错误」，别提前吓人
        for (int i = 1; i <= LoginLock.MAX_FAILURES - 1; i++) {
            JsonNode r = loginRaw("lock-a", "wrong-" + i);
            assertThat(r.path("msg").asText()).as("第 %d 次还不该锁", i)
                    .isEqualTo("账号或密码错误");
        }
        assertThat(userMapper.selectById(id).getFailedAttempts()).isEqualTo(4);
        assertThat(userMapper.selectById(id).getLockedUntil()).isNull();

        // 第 5 次：这一次就锁上，提示必须换口径，否则人以为还是密码错
        JsonNode fifth = loginRaw("lock-a", "wrong-5");
        assertThat(fifth.path("msg").asText())
                .contains("锁定").contains(String.valueOf(LoginLock.LOCK_MINUTES)).contains("管理员");

        // 真正要验的一条：锁着的时候，正确密码也进不去
        JsonNode correct = loginRaw("lock-a", "123456");
        assertThat(correct.path("data").path("token").asText()).as("锁定期间不能发 token").isEmpty();
        assertThat(correct.path("msg").asText()).contains("锁定");

        // 状态落在库里，不是只活在内存里（重启、并发都得认这一份）
        SysUser locked = userMapper.selectById(id);
        assertThat(locked.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(locked.getFailedAttempts()).as("锁定同时清零，解锁后要重新给满次数").isZero();
        assertThat(locked.getLastFailedAt()).isNotNull();
        assertThat(admin).isNotBlank();
    }

    @Test
    @DisplayName("成功登录清零：错 3 次 → 登进去 → 再错 4 次也不锁")
    void successResetsCounter() {
        Long id = user("lock-b", "锁乙", "LOCKER-B");
        loginRaw("lock-b", "wrong-1");
        loginRaw("lock-b", "wrong-2");
        loginRaw("lock-b", "wrong-3");
        assertThat(userMapper.selectById(id).getFailedAttempts()).isEqualTo(3);

        assertThat(loginRaw("lock-b", "123456").path("data").path("token").asText()).isNotBlank();
        assertThat(userMapper.selectById(id).getFailedAttempts()).isZero();

        for (int i = 1; i <= 4; i++) {
            assertThat(loginRaw("lock-b", "wrong-x" + i).path("msg").asText())
                    .isEqualTo("账号或密码错误");
        }
        assertThat(userMapper.selectById(id).getLockedUntil()).as("错 4 次不该锁").isNull();
    }

    @Test
    @DisplayName("锁定过期自动放行，不需要任何人动手")
    void expiredLockLetsYouIn() throws Exception {
        Long id = user("lock-c", "锁丙", "LOCKER-C");
        for (int i = 1; i <= LoginLock.MAX_FAILURES; i++) {
            loginRaw("lock-c", "wrong-" + i);
        }
        assertThat(userMapper.selectById(id).getLockedUntil()).isNotNull();

        // 把截止时间挪到过去，等于「十五分钟过去了」——不真等十五分钟
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getLockedUntil, LocalDateTime.now().minusMinutes(1)));

        assertThat(loginRaw("lock-c", "123456").path("data").path("token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("管理员解锁后立刻能登；解锁本身只有管理员能做")
    void adminCanUnlock() {
        Long id = user("lock-d", "锁丁", "LOCKER-D");
        for (int i = 1; i <= LoginLock.MAX_FAILURES; i++) {
            loginRaw("lock-d", "wrong-" + i);
        }
        assertThat(loginRaw("lock-d", "123456").path("msg").asText()).contains("锁定");

        String admin = adminToken();

        // 检验员与未登录都解不了
        user("lock-d2", "锁丁助手", null);
        String insp = login("lock-d2", "123456");
        assertThat(unlock(insp, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(unlock(null, id).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 越权那两次没把锁顺手解掉
        assertThat(loginRaw("lock-d", "123456").path("msg").asText()).contains("锁定");

        assertThat(unlock(admin, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginRaw("lock-d", "123456").path("data").path("token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("管理员重设密码顺带解锁：不能出现「密码已重置但人还是登不进去」")
    void resetPasswordUnlocks() {
        Long id = user("lock-e", "锁戊", "LOCKER-E");
        for (int i = 1; i <= LoginLock.MAX_FAILURES; i++) {
            loginRaw("lock-e", "wrong-" + i);
        }
        String admin = adminToken();

        ResponseEntity<String> resp = rest.exchange("/api/auth/users/" + id + "/reset-password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("newPassword", "newpass1"), headers(admin)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(loginRaw("lock-e", "newpass1").path("data").path("token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("锁的是账号不是这台机器：锁住甲，乙照常登录；不存在的账号不会被「锁」")
    void lockIsPerAccount() {
        user("lock-f", "锁己", "LOCKER-F");
        user("lock-g", "锁庚", "LOCKER-G");
        for (int i = 1; i <= LoginLock.MAX_FAILURES; i++) {
            loginRaw("lock-f", "wrong-" + i);
        }
        assertThat(loginRaw("lock-f", "123456").path("msg").asText()).contains("锁定");

        // 同一个出口 IP、同一个服务，乙一点不受影响
        assertThat(loginRaw("lock-g", "123456").path("data").path("token").asText()).isNotBlank();

        // 不存在的账号永远是同一句：没有记录可锁，也就不会出现「这个号被锁了」这种破绽
        JsonNode ghost = loginRaw("no-such-user", "whatever");
        assertThat(ghost.path("msg").asText()).isEqualTo("账号或密码错误");
    }

    @Test
    @DisplayName("用户列表把锁定状态算好下发，页面不用自己比时间")
    void listExposesLockState() throws Exception {
        user("lock-h", "锁辛", "LOCKER-H");
        for (int i = 1; i <= LoginLock.MAX_FAILURES; i++) {
            loginRaw("lock-h", "wrong-" + i);
        }
        String admin = adminToken();

        JsonNode users = read(rest.exchange("/api/auth/users", HttpMethod.GET,
                new HttpEntity<>(headers(admin)), String.class).getBody()).path("data");
        JsonNode target = null;
        for (JsonNode u : users) {
            if ("lock-h".equals(u.path("username").asText())) {
                target = u;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.path("locked").asBoolean()).isTrue();
        assertThat(target.path("lockMinutes").asLong()).isBetween(1L, (long) LoginLock.LOCK_MINUTES);
        assertThat(target.path("password").isMissingNode() || target.path("password").isNull())
                .as("哈希永远不下发").isTrue();
    }

    // ==================== 辅助 ====================

    private JsonNode loginRaw(String username, String password) {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), headers(null)),
                String.class);
        return read(resp.getBody());
    }

    private String login(String username, String password) {
        return loginRaw(username, password).path("data").path("token").asText();
    }

    private String adminToken() {
        return login("admin", "admin123");
    }

    private ResponseEntity<String> unlock(String token, Long userId) {
        return rest.exchange("/api/auth/users/" + userId + "/unlock", HttpMethod.POST,
                new HttpEntity<>(headers(token)), String.class);
    }

    /** 用管理员建一个检验员账号，返回它的 id */
    private Long user(String username, String realName, String wecomUserid) {
        String admin = adminToken();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("realName", realName);
        body.put("role", "INSPECTOR");
        body.put("password", "123456");
        body.put("enabled", true);
        body.put("wecomUserid", wecomUserid);
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/users",
                new HttpEntity<>(body, headers(admin)), String.class);
        JsonNode node = read(resp.getBody()).path("data");
        assertThat(node.path("id").asLong()).as("建账号失败：%s", resp.getBody()).isPositive();
        return node.path("id").asLong();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null && !token.isBlank()) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("返回体不是 JSON：" + body, e);
        }
    }
}
