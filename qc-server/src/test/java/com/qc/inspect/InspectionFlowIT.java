package com.qc.inspect;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qc.inspect.entity.QcBatch;
import com.qc.inspect.entity.QcBackupLog;
import com.qc.inspect.entity.QcDefect;
import com.qc.inspect.entity.QcProduct;
import com.qc.inspect.entity.QcStandard;
import com.qc.inspect.entity.QcStandardItem;
import com.qc.inspect.mapper.QcBatchMapper;
import com.qc.inspect.mapper.QcBackupLogMapper;
import com.qc.inspect.mapper.QcDefectMapper;
import com.qc.inspect.mapper.QcProductMapper;
import com.qc.inspect.mapper.QcStandardItemMapper;
import com.qc.inspect.mapper.QcStandardMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

    /* AQL 抽样用例要造一套自己专属的主数据，直接走 mapper 比连调四五个接口省事，
       也更接近「数据已经在库里」的真实起点 */
    @Autowired
    private QcProductMapper productMapper;
    @Autowired
    private QcBatchMapper batchMapper;
    @Autowired
    private QcStandardMapper standardMapper;
    @Autowired
    private QcStandardItemMapper standardItemMapper;
    @Autowired
    private QcDefectMapper defectMapper;
    /* 备份记录平时是外部脚本写的，测试里直接插一行，模拟脚本落库的那一刻 */
    @Autowired
    private QcBackupLogMapper backupLogMapper;

    private static String token;
    private static Long batchId;
    private static Long firstInspectionId;
    private static Long aqlProductId;
    private static Long rkProductId;
    private static Long capProductId;

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

    @Test
    @Order(9)
    @DisplayName("AQL 抽样：批量 300 按国标抽 50 件、Ac=3，依据冻在单上")
    void aqlSamplingFollowsLotSize() {
        Long bigBatch = aqlBatch("IT-AQL-B300", 300);
        Long id = num(data(post("/api/inspections", Map.of("batchId", bigBatch)))
                .get("inspectionId"));

        Map<String, Object> detail = detail(id);
        Map<String, Object> ins = asMap(detail.get("inspection"));

        // 281~500 档、字码 H：n=50、Ac=3。老逻辑下这里会取标准上写死的 1 件，
        // 300 件来料只验 1 件就判批，等于没检
        assertThat(num(ins.get("sampleQty"))).as("批量 300 应抽 50 件").isEqualTo(50L);
        assertThat(num(ins.get("acceptQty"))).as("批量 300 的 Ac 应为 3").isEqualTo(3L);
        assertThat(num(ins.get("lotQty"))).as("批量要一起冻在单上").isEqualTo(300L);
        assertThat(asList(detail.get("pieces"))).as("明细按 50 件展开").hasSize(50);

        // 依据必须能读成人话，否则追溯时只有两个数字，说不清凭什么
        String basis = String.valueOf(ins.get("samplingBasis"));
        assertThat(basis).contains("GB/T 2828.1").contains("AQL 2.5")
                .contains("字码 H").contains("抽 50 件").contains("Ac=3");

        // 批量越大抽得越多：5000 件落到 3201~10000 档（L，n=200，Ac=10）
        Long hugeButValid = aqlBatch("IT-AQL-B5000", 5000);
        Map<String, Object> big = asMap(detail(num(data(post("/api/inspections",
                Map.of("batchId", hugeButValid))).get("inspectionId"))).get("inspection"));
        assertThat(num(big.get("sampleQty"))).as("批量 5000 应抽 200 件").isEqualTo(200L);
        assertThat(num(big.get("acceptQty"))).as("批量 5000 的 Ac 应为 10").isEqualTo(10L);

        // 超出表范围必须拦下：宁可让人拆批，也不能默默按最大档抽 200 件糊过去
        Long tooBig = aqlBatch("IT-AQL-B20000", 20000);
        assertThat(num(post("/api/inspections", Map.of("batchId", tooBig)).get("code")))
                .as("批量超出抽样表范围要报错").isNotEqualTo(0);
    }

    @Test
    @Order(10)
    @DisplayName("出货登记：累计出货不得超发，单号按日流水，客户不能为空")
    void shipmentGuardsOverShip() {
        // 自带一个干净批次：V1 的示例批次 3 件已经全部出完（剩余 0），拿它测不出正常路径
        Long okBatch = aqlBatch("IT-SHIP-OK", 100);
        Long okIns = num(data(post("/api/inspections", Map.of("batchId", okBatch)))
                .get("inspectionId"));
        data(post("/api/inspections/" + okIns + "/submit",
                Map.of("lines", fillCompliant(detail(okIns)))));

        Map<String, Object> ctx = data(get("/api/shipments/context/" + okBatch));
        assertThat(num(ctx.get("batchQty"))).isEqualTo(100L);
        assertThat(num(ctx.get("shippedQty"))).isZero();
        assertThat(num(ctx.get("remainingQty"))).isEqualTo(100L);
        // 已提交且判合格的批次，出货不该被拦
        assertThat(ctx.get("result")).isEqualTo("PASS");
        assertThat(ctx.get("blocking")).as("合格批次不该有拦截理由").isNull();

        Map<String, Object> s = data(post("/api/shipments",
                Map.of("batchId", okBatch, "customer", "IT 客户", "qty", 30)));
        // 单号必须可读可检索：客诉时人手里只有这个号
        assertThat(String.valueOf(s.get("shipmentNo"))).matches("SH\\d{11}");

        Map<String, Object> after = data(get("/api/shipments/context/" + okBatch));
        assertThat(num(after.get("shippedQty"))).isEqualTo(30L);
        assertThat(num(after.get("remainingQty"))).isEqualTo(70L);

        // 30 + 80 = 110 > 100：超发必须拦，出货记录是追溯终点，数量对不上追溯结论就不可信
        Map<String, Object> over = post("/api/shipments",
                Map.of("batchId", okBatch, "customer", "IT 客户", "qty", 80));
        assertThat(over.get("code")).as("累计超发要被拦住").isNotEqualTo(0);

        // 客户留空等于没有追溯终点，force 也放不过去
        assertThat(post("/api/shipments",
                Map.of("batchId", okBatch, "customer", "  ", "qty", 1, "force", true)).get("code"))
                .as("客户不能为空").isNotEqualTo(0);

        // 剩余量刚好用完是允许的，边界不做「少一件」的保守处理
        Map<String, Object> fill = data(post("/api/shipments",
                Map.of("batchId", okBatch, "customer", "IT 客户", "qty", 70)));
        assertThat(fill.get("shipmentNo")).isNotNull();
        assertThat(num(data(get("/api/shipments/context/" + okBatch)).get("remainingQty"))).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("没有检验结论的批次默认不许出货，例外放行必须留痕")
    void shipmentRequiresConclusion() {
        // aqlBatch 只建批次不开单，正好是「货到了但还没检」的状态
        Long raw = aqlBatch("IT-SHIP-NOCHK", 100);

        Map<String, Object> ctx = data(get("/api/shipments/context/" + raw));
        assertThat(ctx.get("result")).isNull();
        assertThat(String.valueOf(ctx.get("blocking"))).contains("还没有任何检验结论");

        Map<String, Object> blocked = post("/api/shipments",
                Map.of("batchId", raw, "customer", "IT 客户", "qty", 10));
        assertThat(blocked.get("code")).as("没结论不能直接出货").isNotEqualTo(0);

        Map<String, Object> forced = data(post("/api/shipments",
                Map.of("batchId", raw, "customer", "IT 客户", "qty", 10, "force", true)));
        // 一年后看到这张单，要能一眼认出它当年是带着问题出去的
        assertThat(String.valueOf(forced.get("remark")))
                .contains("[例外放行]").contains("还没有任何检验结论");
    }

    @Test
    @Order(12)
    @DisplayName("筛选口径：列表、导出、供应商统计三处必须对得上")
    void filtersAndSupplierStat() {
        // 单号/批次号同一个输入框：主管手里通常只有一个号，记不清是哪一种
        Map<String, Object> byKeyword = data(
                get("/api/inspections/page?current=1&size=50&keyword=IT-AQL-B300"));
        List<Map<String, Object>> recs = asList(byKeyword.get("records"));
        assertThat(recs).isNotEmpty();
        for (Map<String, Object> r : recs) {
            assertThat(String.valueOf(r.get("batchNo"))).contains("IT-AQL-B300");
        }

        Map<String, Object> passOnly = data(get("/api/inspections/page?current=1&size=50&result=PASS"));
        List<Map<String, Object>> passRecs = asList(passOnly.get("records"));
        assertThat(passRecs).isNotEmpty();
        for (Map<String, Object> r : passRecs) {
            assertThat(r.get("result")).isEqualTo("PASS");
        }

        // 导出走的是同一份 InspectionQuery：屏幕上 3 条，CSV 里也必须是 3 条 + 表头
        ResponseEntity<byte[]> resp = rest.exchange("/api/export/inspections?keyword=IT-AQL-B300",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] body = resp.getBody();
        assertThat(body).isNotNull();
        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        long dataLines = csv.lines().filter(l -> l.startsWith("QC")).count();
        assertThat(dataLines).as("导出条数应与列表一致").isEqualTo(recs.size());

        // 供应商统计：件级合格率升序，最差的排前面
        List<Map<String, Object>> sup = asList(get("/api/batches/stat/supplier").get("data"));
        assertThat(sup).isNotEmpty();
        assertThat(sup).as("没填供应商的批次要单独成组，不能吞掉")
                .anyMatch(r -> "（未填供应商）".equals(r.get("supplier")));

        double prev = -1;
        for (Map<String, Object> r : sup) {
            long total = num(r.get("piece_total"));
            if (total == 0) {
                continue;
            }
            double rate = (double) num(r.get("piece_pass")) / total;
            assertThat(rate).as("供应商应按件级合格率升序，差的在前").isGreaterThanOrEqualTo(prev);
            prev = rate;
        }
    }

    @Test
    @Order(13)
    @DisplayName("返工复检：未返工不许开复检，复检不合格不许算返工完成")
    void reworkRecheckLoop() {
        Long rkBatch = rkBatch("IT-RK-B1");
        Long id = num(data(post("/api/inspections", Map.of("batchId", rkBatch))).get("inspectionId"));

        // 先判一张 NG 单：不良记录是复检闭环的起点，没有它整条链无从谈起
        data(post("/api/inspections/" + id + "/submit",
                Map.of("lines", fillViolating(detail(id)))));
        assertThat(asMap(detail(id).get("inspection")).get("result")).isEqualTo("FAIL");

        List<Map<String, Object>> defects = asList(get("/api/defects?inspectionId=" + id).get("data"));
        assertThat(defects).as("判 NG 必须落一条不良").hasSize(1);
        Long defectId = num(defects.get(0).get("id"));
        // 缺陷判出来时处置就预填成 REWORK，但那只是「建议怎么处理」，不是已执行
        assertThat(defects.get(0).get("disposition")).isEqualTo("REWORK");
        assertThat(defects.get(0).get("dispositionStatus")).isEqualTo("PENDING");

        // 还没返工就开复检单 —— 验的还是原来那批坏件，结论会被当成返工有效的证据
        assertThat(num(post("/api/defects/" + defectId + "/recheck/open", Map.of()).get("code")))
                .as("返工开始前不许开复检单").isNotEqualTo(0);

        data(post("/api/defects/" + defectId + "/dispose",
                Map.of("disposition", "REWORK", "status", "PROCESSING")));

        // 模拟「这一条不良涉及 3 件返工」，用来验证复检件数跟着不良件数走
        defectMapper.update(null, new LambdaUpdateWrapper<QcDefect>()
                .eq(QcDefect::getId, defectId).set(QcDefect::getDefectQty, 3));

        Map<String, Object> rk = data(post("/api/defects/" + defectId + "/recheck/open", Map.of()));
        Long recheckId = num(rk.get("inspectionId"));
        assertThat(num(rk.get("sampleQty"))).as("返工 3 件就验 3 件，不按批量重抽").isEqualTo(3L);
        assertThat(num(rk.get("acceptQty"))).as("复检 Ac 恒为 0").isZero();
        assertThat(num(rk.get("originInspectionId"))).isEqualTo(id);

        Map<String, Object> rdetail = detail(recheckId);
        Map<String, Object> rins = asMap(rdetail.get("inspection"));
        assertThat(num(rins.get("recheckOfId"))).as("复检单必须能追回原单").isEqualTo(id);
        assertThat(num(rins.get("recheckDefectId"))).isEqualTo(defectId);
        assertThat(asList(rdetail.get("pieces"))).as("按返工件数展开明细").hasSize(3);
        // 判据沿用原单快照：标准日后改版，复检和原单也必须能对比
        assertThat(String.valueOf(rins.get("samplingBasis"))).contains("返工复检").contains("Ac=0");

        // 重复开单会造出没人管的孤儿单
        assertThat(num(post("/api/defects/" + defectId + "/recheck/open", Map.of()).get("code")))
                .as("一条不良只许一张复检单").isNotEqualTo(0);

        // 复检单还是草稿，返工不能标完成
        assertThat(num(post("/api/defects/" + defectId + "/dispose",
                Map.of("disposition", "REWORK", "status", "DONE")).get("code")))
                .as("复检没提交不许标完成").isNotEqualTo(0);

        // 复检不合格：返工没修好，同样不许闭环
        data(post("/api/inspections/" + recheckId + "/submit",
                Map.of("lines", fillViolating(detail(recheckId)))));
        assertThat(asMap(detail(recheckId).get("inspection")).get("result")).isEqualTo("FAIL");
        assertThat(num(post("/api/defects/" + defectId + "/dispose",
                Map.of("disposition", "REWORK", "status", "DONE")).get("code")))
                .as("复检不合格不许标完成").isNotEqualTo(0);

        // 重新返工后复检合格：解锁补录正确值再提交
        data(post("/api/inspections/" + recheckId + "/reopen?reason=返工后复检补录",
                Map.of()));
        data(post("/api/inspections/" + recheckId + "/submit",
                Map.of("lines", fillCompliant(detail(recheckId)))));
        assertThat(asMap(detail(recheckId).get("inspection")).get("result")).isEqualTo("PASS");

        data(post("/api/defects/" + defectId + "/dispose",
                Map.of("disposition", "REWORK", "status", "DONE")));
        Map<String, Object> done = asList(get("/api/defects?inspectionId=" + id).get("data")).get(0);
        assertThat(done.get("dispositionStatus")).isEqualTo("DONE");
        // 列表上要能直接看出复检结论，否则还得再点进单据才敢说返工修好了
        assertThat(String.valueOf(done.get("recheckInspectionNo"))).startsWith("QC");
        assertThat(done.get("recheckResult")).isEqualTo("PASS");
    }

    @Test
    @Order(14)
    @DisplayName("出货作废（红冲）：理由必填，作废后批次额度回退且能重新出货")
    void shipmentVoidRollsBackQuota() {
        Long b = aqlBatch("IT-SHIP-VOID", 100);
        Long ins = num(data(post("/api/inspections", Map.of("batchId", b))).get("inspectionId"));
        data(post("/api/inspections/" + ins + "/submit",
                Map.of("lines", fillCompliant(detail(ins)))));

        Map<String, Object> s = data(post("/api/shipments",
                Map.of("batchId", b, "customer", "IT 客户", "qty", 100)));
        Long sid = num(s.get("id"));
        assertThat(s.get("status")).isEqualTo("NORMAL");
        assertThat(num(data(get("/api/shipments/context/" + b)).get("remainingQty"))).isZero();

        // 作废是抹掉一条出货事实，不写理由等于留下一笔没人看得懂的账
        assertThat(num(post("/api/shipments/" + sid + "/void", Map.of("reason", "   ")).get("code")))
                .as("作废不写理由要拦").isNotEqualTo(0);

        Map<String, Object> v = data(post("/api/shipments/" + sid + "/void",
                Map.of("reason", "客户取消订单")));
        assertThat(v.get("status")).isEqualTo("VOID");
        assertThat(v.get("voidReason")).isEqualTo("客户取消订单");
        assertThat(String.valueOf(v.get("voidedBy"))).isNotBlank();

        // 额度回退：这是作废的全部意义。若不回退，作废完还是出不了第二单，
        // 现场只能改用纸单，等于系统被绕过去
        Map<String, Object> ctx = data(get("/api/shipments/context/" + b));
        assertThat(num(ctx.get("shippedQty"))).isZero();
        assertThat(num(ctx.get("remainingQty"))).isEqualTo(100L);

        // 两个人同时点、或者手滑点两下，第二次必须被拦
        assertThat(num(post("/api/shipments/" + sid + "/void", Map.of("reason", "再销一次")).get("code")))
                .as("不能重复作废").isNotEqualTo(0);

        // 作废不是删除：列表里按状态还能筛出来，红冲痕迹要留
        List<Map<String, Object>> voids = asList(data(
                get("/api/shipments/page?current=1&size=20&status=VOID&keyword=IT-SHIP-VOID")).get("records"));
        assertThat(voids).as("作废单仍可检索").hasSize(1);
        assertThat(voids.get(0).get("batchNo")).isEqualTo("IT-SHIP-VOID");
        assertThat(voids.get(0).get("voidReason")).isEqualTo("客户取消订单");

        // 额度腾出来后可以重新登记
        Map<String, Object> again = data(post("/api/shipments",
                Map.of("batchId", b, "customer", "IT 客户", "qty", 100)));
        assertThat(again.get("status")).isEqualTo("NORMAL");
        assertThat(num(data(get("/api/shipments/context/" + b)).get("remainingQty"))).isZero();
    }

    @Test
    @Order(15)
    @DisplayName("主数据：编号不许撞车，枚举项没选项、布尔项没说合格值都不许存")
    void masterDataGuards() {
        Map<String, Object> p = data(post("/api/products",
                Map.of("productCode", "IT-MD-P1", "productName", "主数据试用件", "unit", "件")));
        Long pid = num(p.get("id"));

        // 唯一索引也会拦，但抛出来的是 duplicate key value violates...
        // 车间的人看不懂，只会当成系统坏了又回去用 Excel
        assertThat(num(post("/api/products",
                Map.of("productCode", "IT-MD-P1", "productName", "重复编号")).get("code")))
                .as("产品编号不能重复").isNotEqualTo(0);

        // 产品一览要能回答「这个产品能不能开单」——没有启用标准就是不能
        List<Map<String, Object>> ov = asList(get("/api/products/overview?keyword=IT-MD-P1").get("data"));
        assertThat(ov).hasSize(1);
        assertThat(num(ov.get(0).get("active_standard_count"))).as("新产品还没有启用标准").isZero();
        assertThat(num(ov.get(0).get("batch_count"))).isZero();

        // 检验类型不填默认 FQC，填了就得是那四个之一
        Map<String, Object> b = data(post("/api/batches",
                Map.of("batchNo", "IT-MD-B1", "productId", pid, "qty", 10)));
        assertThat(b.get("inspectType")).isEqualTo("FQC");
        assertThat(b.get("status")).isEqualTo("PENDING");

        assertThat(num(post("/api/batches",
                Map.of("batchNo", "IT-MD-B1", "productId", pid, "qty", 10)).get("code")))
                .as("同一批料重复登记要拦").isNotEqualTo(0);
        assertThat(num(post("/api/batches",
                Map.of("batchNo", "IT-MD-B2", "productId", 99999999L, "qty", 10)).get("code")))
                .as("产品不存在要拦").isNotEqualTo(0);
        assertThat(num(post("/api/batches",
                Map.of("batchNo", "IT-MD-B3", "productId", pid, "qty", 10,
                        "inspectType", "XYZ")).get("code")))
                .as("检验类型只能是 IQC/IPQC/FQC/OQC").isNotEqualTo(0);

        // 枚举项没选项，录入时无值可选；布尔项没说合格值，判定层只能猜方向
        assertThat(num(post("/api/items",
                Map.of("itemCode", "IT-I-ENUM", "itemName", "枚举项", "valueType", "ENUM")).get("code")))
                .as("枚举项缺选项要拦").isNotEqualTo(0);
        assertThat(num(post("/api/items",
                Map.of("itemCode", "IT-I-BOOL", "itemName", "布尔项", "valueType", "BOOL")).get("code")))
                .as("布尔项没说合格值要拦").isNotEqualTo(0);

        Map<String, Object> it = data(post("/api/items", Map.of(
                "itemCode", "IT-I-ENUM", "itemName", "表面划伤", "valueType", "ENUM",
                "optionsJson", "[{\"code\":\"NONE\",\"name\":\"无\",\"pass\":true},"
                        + "{\"code\":\"HEAVY\",\"name\":\"严重\",\"pass\":false}]")));
        assertThat(num(it.get("id"))).isNotNull();
    }

    @Test
    @Order(16)
    @DisplayName("工作台：数字与列表同源，待处置带批次号，草稿只算自己的")
    void todoWorkbench() {
        // 一张刚提交的单，必然躺在「待审核」里
        Long b = aqlBatch("IT-TODO-PASS", 20);
        Long passIns = num(data(post("/api/inspections", Map.of("batchId", b))).get("inspectionId"));
        data(post("/api/inspections/" + passIns + "/submit",
                Map.of("lines", fillCompliant(detail(passIns)))));

        Map<String, Object> sum = data(get("/api/todo/summary"));
        assertThat(num(sum.get("pendingReview"))).as("刚提交的单必须进待审核").isPositive();
        // actionable 只由「要人动手」的几项相加，量具超期不算单据，混进来会让人以为有几十张单没处理。
        // 待检批次算：料躺在厂里没人检，是明确等人去开单的一件事，看不见就等于不存在
        assertThat(num(sum.get("actionable")))
                .as("角标总数口径")
                .isEqualTo(num(sum.get("pendingReview")) + num(sum.get("pendingDefect"))
                        + num(sum.get("myRejected")) + num(sum.get("pendingBatch")));

        List<Map<String, Object>> reviews = asList(get("/api/todo/reviews").get("data"));
        assertThat(reviews).extracting(r -> num(r.get("id"))).contains(passIns);
        // 工作台只看头部，全量翻页去检验单列表；不加限制时单量大了一次拉几千行
        assertThat(asList(get("/api/todo/reviews?limit=1").get("data"))).hasSize(1);

        // 待处置不良必须带批次号：处置人拿着它去现场找料，只有检验单号还得再点进去看
        Long ngBatch = aqlBatch("IT-TODO-NG", 20);
        Long ngIns = num(data(post("/api/inspections", Map.of("batchId", ngBatch))).get("inspectionId"));
        data(post("/api/inspections/" + ngIns + "/submit",
                Map.of("lines", fillViolating(detail(ngIns)))));

        Map<String, Object> ngDefect = asList(get("/api/todo/defects").get("data")).stream()
                .filter(d -> ngIns.equals(num(d.get("inspectionId"))))
                .findFirst().orElseThrow();
        assertThat(ngDefect.get("batchNo")).isEqualTo("IT-TODO-NG");

        // 草稿是检验员自己的活，不该出现在别人的待办里
        Long draftBatch = aqlBatch("IT-TODO-DRAFT", 20);
        Long draftIns = num(data(post("/api/inspections",
                Map.of("batchId", draftBatch))).get("inspectionId"));
        Map<String, Object> mine = data(get("/api/todo/mine"));
        assertThat(asList(mine.get("drafts"))).extracting(r -> num(r.get("id"))).contains(draftIns);
        assertThat(num(data(get("/api/todo/summary")).get("myDraft"))).isPositive();
    }

    @Test
    @Order(17)
    @DisplayName("量具校准履历：同日不许重登、补录不动有效期、不合格停用且不许用于检验")
    void gaugeCalibrationHistory() {
        LocalDate today = LocalDate.now();

        // ---- 量具 A：正常送检 ----
        Long a = num(data(post("/api/gauges", Map.of(
                "gaugeCode", "IT-G-A", "gaugeName", "IT 数显卡尺", "gaugeType", "卡尺",
                "calibrateDate", today.minusDays(300).toString(), "calibrateCycleDays", 365))).get("id"));

        // 建档时填的校准日期也必须成为一条履历，否则新量具在履历页上跟「从没校准过」一样
        assertThat(asList(get("/api/gauges/" + a + "/calibrations").get("data")))
                .as("建档即落一条履历").hasSize(1);

        Map<String, Object> after = data(post("/api/gauges/" + a + "/calibrate", Map.of(
                "calibrateDate", today.toString(), "cycleDays", 180, "result", "PASS",
                "certificateNo", "JL-2026-0888", "agency", "市计量院")));
        assertThat(after.get("nextCalibrateDate")).isEqualTo(today.plusDays(180).toString());
        assertThat(after.get("status")).isEqualTo("ACTIVE");

        List<Map<String, Object>> his = asList(get("/api/gauges/" + a + "/calibrations").get("data"));
        assertThat(his).hasSize(2);
        // 倒序：最新那次在最前，外审翻履历第一眼看到的就是当前的
        assertThat(his.get(0).get("certificateNo")).isEqualTo("JL-2026-0888");
        assertThat(String.valueOf(his.get(0).get("calibrateBy"))).isNotBlank();

        // 手抖点两下、两个人同时登记，都不能长出两条一样的记录
        assertThat(num(post("/api/gauges/" + a + "/calibrate",
                Map.of("calibrateDate", today.toString())).get("code")))
                .as("同一天不许重复登记").isNotEqualTo(0);

        // 校准证书晚到一个月是常态，补录去年的记录不该把当前有效期改回去
        data(post("/api/gauges/" + a + "/calibrate", Map.of(
                "calibrateDate", today.minusDays(500).toString(), "cycleDays", 365,
                "certificateNo", "JL-2024-0001")));
        assertThat(asList(get("/api/gauges/" + a + "/calibrations").get("data")))
                .as("补录进了履历").hasSize(3);
        assertThat(gauge(a).get("nextCalibrateDate"))
                .as("补录旧记录不动当前有效期").isEqualTo(today.plusDays(180).toString());

        // ---- 量具 B：校准不合格 ----
        Long b = num(data(post("/api/gauges", Map.of(
                "gaugeCode", "IT-G-B", "gaugeName", "IT 千分尺", "gaugeType", "千分尺",
                "calibrateDate", today.minusDays(100).toString(), "calibrateCycleDays", 365))).get("id"));
        String expiry = String.valueOf(gauge(b).get("nextCalibrateDate"));

        // 限用不写清范围，下一个人会拿它去测本来不该测的尺寸
        assertThat(num(post("/api/gauges/" + b + "/calibrate",
                Map.of("calibrateDate", today.minusDays(2).toString(), "result", "LIMITED")).get("code")))
                .as("限用必须写清限制范围").isNotEqualTo(0);

        Map<String, Object> failed = data(post("/api/gauges/" + b + "/calibrate", Map.of(
                "calibrateDate", today.toString(), "result", "FAIL",
                "certificateNo", "JL-2026-0999", "agency", "市计量院",
                "remark", "示值超差 0.03mm")));
        assertThat(failed.get("status")).as("校准不合格必须停用").isEqualTo("SUSPENDED");
        assertThat(failed.get("nextCalibrateDate")).as("不合格不顺延有效期").isEqualTo(expiry);

        // 停用的量具不能出现在「可用」列表里（录入页就是按 ACTIVE 取数）
        assertThat(asList(get("/api/gauges?status=ACTIVE").get("data")))
                .extracting(g -> num(g.get("id"))).doesNotContain(b);

        // 真正的闸门在提交：拿停用量具量出来的数据不许入账
        Long nb = aqlBatch("IT-G-BATCH", 20);
        Long ins = num(data(post("/api/inspections", Map.of("batchId", nb))).get("inspectionId"));
        List<Map<String, Object>> lines = fillCompliant(detail(ins));
        lines.get(0).put("gaugeId", b);
        Map<String, Object> blocked = post("/api/inspections/" + ins + "/submit", Map.of("lines", lines));
        assertThat(num(blocked.get("code"))).as("停用量具不许用于检验").isNotEqualTo(0);
        assertThat(String.valueOf(blocked.get("msg"))).contains("停用");

        // 修好重新校准合格（哪怕日期比上次登记早，只要更新就恢复）
        Map<String, Object> fixed = data(post("/api/gauges/" + b + "/calibrate", Map.of(
                "calibrateDate", today.minusDays(1).toString(), "cycleDays", 180, "result", "PASS",
                "certificateNo", "JL-2026-1001", "agency", "市计量院", "remark", "维修后复校")));
        assertThat(fixed.get("status")).as("重新校准合格即恢复可用").isEqualTo("ACTIVE");
        assertThat(fixed.get("nextCalibrateDate")).isEqualTo(today.minusDays(1).plusDays(180).toString());
    }

    @Test
    @Order(18)
    @DisplayName("操作日志：动作可筛、按批次号可查、非管理员看不到、可导出")
    void auditLogs() {
        Long b = aqlBatch("IT-AUDIT", 20);
        Long ins = num(data(post("/api/inspections", Map.of("batchId", b))).get("inspectionId"));
        data(post("/api/inspections/" + ins + "/submit",
                Map.of("lines", fillCompliant(detail(ins)))));
        data(post("/api/inspections/" + ins + "/void?reason=IT 重复建单，作废", Map.of()));
        assertThat(String.valueOf(asMap(detail(ins).get("inspection")).get("status"))).isEqualTo("VOID");

        // 按批次号查：来查的人手里常常只有一个号，记不清是单号还是批次号
        List<Map<String, Object>> rows = asList(
                data(get("/api/audit/logs?current=1&size=50&keyword=IT-AUDIT")).get("records"));
        assertThat(rows).as("这张单的一串动作都要能捞出来").isNotEmpty();
        assertThat(rows).extracting(r -> r.get("action"))
                .as("建单/提交/作废三次留痕").contains("CREATE", "SUBMIT", "VOID");
        assertThat(rows).allSatisfy(r -> assertThat(String.valueOf(r.get("batchNo"))).isEqualTo("IT-AUDIT"));

        // 查日志的人要能直接看到哪张单、哪个批次，而不是一个光秃秃的 id
        Map<String, Object> create = rows.stream()
                .filter(r -> "CREATE".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(create.get("inspectionNo")).isNotNull();
        assertThat(create.get("productName")).isNotNull();
        assertThat(num(create.get("inspectionId"))).isEqualTo(ins);

        // 作废理由是复核的关键，必须原样留在这条记录上
        Map<String, Object> voidRow = rows.stream()
                .filter(r -> "VOID".equals(r.get("action"))).findFirst().orElseThrow();
        assertThat(String.valueOf(voidRow.get("reason"))).contains("重复建单");
        assertThat(voidRow.get("toStatus")).isEqualTo("VOID");
        assertThat(String.valueOf(voidRow.get("operator"))).isNotBlank();

        // 动作筛选与服务端一致
        assertThat(asList(data(get("/api/audit/logs?current=1&size=20&action=VOID")).get("records")))
                .allSatisfy(r -> assertThat(r.get("action")).isEqualTo("VOID"));

        // 导出：内审要的是能打印的凭证，不是让人一页页截图
        ResponseEntity<byte[]> resp = rest.exchange("/api/export/audit-logs?keyword=IT-AUDIT",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(new byte[]{body[0], body[1], body[2]})
                .isEqualTo(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("发生时间").contains("作废").contains("IT 重复建单");

        // 日志里有作废理由和退回意见，等于把争议记录摊开，不是所有人都该随手翻
        String adminToken = token;
        token = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        try {
            assertThat(statusOf("/api/audit/logs")).as("非管理员看不了操作日志").isEqualTo(403);
            assertThat(statusOf("/api/export/audit-logs")).isEqualTo(403);
        } finally {
            token = adminToken;
        }
    }

    @Test
    @Order(19)
    @DisplayName("客户投诉：登记校验、未关闭拦出货、追溯可见、关闭需根因/措施/责任方、可导出")
    void customerComplaints() {
        Long b = aqlBatch("IT-TS", 40);
        // 先做出一个合格结论：否则出货拦截会被「还没有检验结论」顶掉，
        // 这条用例真正要验的「客诉拦出货」就测不到了
        Long ins = num(data(post("/api/inspections", Map.of("batchId", b))).get("inspectionId"));
        data(post("/api/inspections/" + ins + "/submit",
                Map.of("lines", fillCompliant(detail(ins)))));

        // ---- 登记门槛 ----
        // 没批次的客诉无法追溯也没法定责，只能算抱怨，不许入库
        assertBizFail(post("/api/complaints",
                Map.of("customer", "苏州某机械", "description", "外径超差")), "批次");
        assertBizFail(post("/api/complaints",
                Map.of("batchId", b, "description", "外径超差")), "客户");
        assertBizFail(post("/api/complaints",
                Map.of("batchId", b, "customer", "苏州某机械")), "描述");
        assertBizFail(post("/api/complaints", Map.of("batchId", b, "customer", "苏州某机械",
                "description", "外径超差", "complaintDate", LocalDate.now().plusDays(1).toString())),
                "不能晚于今天");
        // 类别是质量体系的固定用语，不能现场自造
        assertBizFail(post("/api/complaints", Map.of("batchId", b, "customer", "苏州某机械",
                "description", "外径超差", "category", "UGLY")), "取值不合法");

        Map<String, Object> created = data(post("/api/complaints", Map.of(
                "batchId", b, "customer", "苏州某机械", "description", "外径超差，装配后卡死",
                "severity", "HIGH", "category", "SIZE", "qty", 3)));
        Long cid = num(created.get("id"));
        assertThat(String.valueOf(created.get("complaintNo"))).startsWith("TS");
        assertThat(created.get("status")).isEqualTo("OPEN");
        // 登记完直接能看出是哪一批，不必再点一次换取批次号
        assertThat(created.get("batchNo")).isEqualTo("IT-TS");

        // ---- 追溯链的第五块 ----
        assertThat(asList(get("/api/complaints?batchId=" + b).get("data"))).hasSize(1);
        Map<String, Object> trace = data(get("/api/trace/batch/" + b));
        assertThat(asList(trace.get("complaints"))).as("追溯页要能看到客诉").hasSize(1);
        assertThat(num(trace.get("openComplaints"))).isEqualTo(1L);

        // ---- 未关闭客诉拦出货 ----
        // 客户已经投诉了这批货、还没查清又发给下一个客户，等于把同一个问题复制出去
        assertBizFail(post("/api/shipments",
                Map.of("batchId", b, "customer", "无锡某厂", "qty", 5)), "未关闭的客户投诉");
        assertThat(num(data(get("/api/shipments/context/" + b)).get("openComplaints"))).isEqualTo(1L);

        // 例外放行能出，但必须留痕：一年后看这张单要知道它当年是带着客诉出去的
        Map<String, Object> forced = data(post("/api/shipments",
                Map.of("batchId", b, "customer", "无锡某厂", "qty", 5, "force", true)));
        assertThat(String.valueOf(forced.get("remark")))
                .contains("[例外放行]").contains("未关闭的客户投诉");

        // ---- 关闭：三项缺一不可 ----
        assertBizFail(post("/api/complaints/" + cid + "/close",
                Map.of("action", "加严抽检", "responsibility", "本厂制程")), "根本原因");
        assertBizFail(post("/api/complaints/" + cid + "/close",
                Map.of("rootCause", "车刀磨损", "responsibility", "本厂制程")), "措施");
        assertBizFail(post("/api/complaints/" + cid + "/close",
                Map.of("rootCause", "车刀磨损", "action", "加严抽检")), "责任归属");

        Map<String, Object> closed = data(post("/api/complaints/" + cid + "/close", Map.of(
                "rootCause", "车刀磨损未按周期更换",
                "action", "换刀频次由 500 件改为 300 件，首件必测",
                "responsibility", "本厂制程")));
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(String.valueOf(closed.get("closedBy"))).isNotBlank();
        assertThat(closed.get("closedAt")).isNotNull();

        // 关闭记录一旦落定，不许被重复关闭覆盖根因分析
        assertBizFail(post("/api/complaints/" + cid + "/close",
                Map.of("rootCause", "随便写", "action", "随便写", "responsibility", "供应商")), "已关闭");

        // 关掉之后这个批次才恢复可以正常出货
        assertThat(num(data(get("/api/shipments/context/" + b)).get("openComplaints"))).isZero();
        data(post("/api/shipments", Map.of("batchId", b, "customer", "无锡某厂", "qty", 5)));

        // ---- 列表与导出同口径 ----
        List<Map<String, Object>> openRows = asList(data(
                get("/api/complaints/page?current=1&size=20&open=true")).get("records"));
        assertThat(openRows).allSatisfy(r -> assertThat(r.get("status")).isNotEqualTo("CLOSED"));
        List<Map<String, Object>> kwRows = asList(data(
                get("/api/complaints/page?current=1&size=20&keyword=IT-TS")).get("records"));
        assertThat(kwRows).as("按批次号也要能搜到客诉").hasSize(1);

        ResponseEntity<byte[]> resp = rest.exchange("/api/export/complaints?keyword=IT-TS",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(new byte[]{body[0], body[1], body[2]})
                .isEqualTo(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("客诉单号").contains("苏州某机械").contains("车刀磨损");

        // ---- 谁能关闭 ----
        // 「问题解决了」这句话不能由接电话的人自己说，检验员关不了
        Map<String, Object> second = data(post("/api/complaints", Map.of(
                "batchId", b, "customer", "常州某厂", "description", "包装破损", "category", "PACK")));
        Long cid2 = num(second.get("id"));
        String adminToken = token;
        token = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        try {
            assertThat(statusOfPost("/api/complaints/" + cid2 + "/close",
                    Map.of("rootCause", "a", "action", "b", "responsibility", "c")))
                    .as("检验员不能关闭客诉").isEqualTo(403);
            // 但登记对所有人开放：客诉往往是销售、客服先接到的，只有质检能录就会烂在微信里
            Map<String, Object> byInspector = data(post("/api/complaints", Map.of(
                    "batchId", b, "customer", "常州某厂", "description", "标识贴反")));
            assertThat(String.valueOf(byInspector.get("complaintNo"))).startsWith("TS");
        } finally {
            token = adminToken;
        }
    }

    @Test
    @Order(20)
    @DisplayName("过程能力：Cpk 按组内移动极差算、草稿不计入、超规值不剔除")
    void capabilityAnalysis() {
        // 两张单、每张 14 件，实测值在 15.1 / 14.9 之间交替。
        // 这样设计是为了让每个数字都能手算核对：
        //   mean = 15.000，单内相邻极差恒为 0.2 → MR̄ = 0.2
        //   σ̂ = MR̄ / 1.128 = 0.177305，Cp = Cpk = 1 / (3 × 0.177305) = 1.880
        Long b1 = capBatch("IT-CAP-1");
        Long i1 = num(data(post("/api/inspections", Map.of("batchId", b1))).get("inspectionId"));
        Map<String, Object> d1 = detail(i1);
        assertThat(rows(d1)).as("固定抽 14 件").hasSize(14);
        data(post("/api/inspections/" + i1 + "/submit",
                Map.of("lines", capLines(d1, "15.1", "14.9"))));

        Long b2 = capBatch("IT-CAP-2");
        Long i2 = num(data(post("/api/inspections", Map.of("batchId", b2))).get("inspectionId"));
        data(post("/api/inspections/" + i2 + "/submit",
                Map.of("lines", capLines(detail(i2), "15.1", "14.9"))));

        String url = "/api/stat/capability?productId=" + capProductId + "&itemId=1&from="
                + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now();
        Map<String, Object> cap = data(get(url));

        assertThat(num(cap.get("n"))).as("两张单 14×2 个实测值").isEqualTo(28L);
        assertThat(num(cap.get("inspectionCount"))).isEqualTo(2L);
        assertThat(dec(cap.get("mean"))).as("均值").isEqualByComparingTo("15");
        assertThat(dec(cap.get("meanMr"))).as("组内移动极差均值").isEqualByComparingTo("0.2");
        // 逗号后面留余量：σ̂ 是 0.177305 这类无限小数，四舍五入到三位后会有尾差
        assertThat(dec(cap.get("cp")).doubleValue()).isBetween(1.86, 1.89);
        assertThat(dec(cap.get("cpk")).doubleValue()).isBetween(1.86, 1.89);
        assertThat(num(cap.get("outOfSpec"))).as("全在 14~16 之内").isZero();
        // 1.88 ≥ 1.67 落在优秀档（阈值：1.67 优秀 / 1.33 充足 / 1.00 勉强 / 不足）
        assertThat(cap.get("verdict")).isEqualTo("EXCELLENT");
        // 28 ≥ 25，不该再弹「样本不足」的提示
        assertThat(String.valueOf(cap.get("notes"))).doesNotContain("不足 25");

        // 直方图与控制图是给人看的东西，至少要给出对应结构，别让前端拿到 null 白屏
        Map<String, Object> hist = asMap(cap.get("histogram"));
        assertThat(asList(hist.get("bins"))).as("直方图分箱").isNotEmpty();
        Map<String, Object> chart = asMap(cap.get("controlChart"));
        assertThat(asList(asMap(chart.get("individual")).get("points")))
                .as("单值图应有 28 个点").hasSize(28);
        assertThat(asList(asMap(chart.get("movingRange")).get("points")))
                .as("移动极差 13×2 个点").hasSize(26);

        // ---- 草稿不计入 ----
        // 草稿还在改，把它算进来会让 Cpk 随「谁正在录单」跳动，这种数没人敢用
        Long b3 = capBatch("IT-CAP-DRAFT");
        Long i3 = num(data(post("/api/inspections", Map.of("batchId", b3))).get("inspectionId"));
        data(post("/api/inspections/" + i3 + "/save",
                Map.of("lines", capLines(detail(i3), "99", "99"))));
        Map<String, Object> withDraft = data(get(url));
        assertThat(num(withDraft.get("n"))).as("草稿的 14 个 99 不能算进来").isEqualTo(28L);
        assertThat(num(withDraft.get("mean")) == null).isFalse();
        assertThat(dec(withDraft.get("mean"))).isEqualByComparingTo("15");

        // ---- 超规值不剔除 ----
        Long b4 = capBatch("IT-CAP-OOS");
        Long i4 = num(data(post("/api/inspections", Map.of("batchId", b4))).get("inspectionId"));
        // 14 个 16.5 全都超上限。如果实现里把超规值剔掉，outOfSpec 会是 0、Cpk 反而不降
        data(post("/api/inspections/" + i4 + "/submit",
                Map.of("lines", capLines(detail(i4), "16.5", "16.5"))));
        Map<String, Object> withOos = data(get(url));
        assertThat(num(withOos.get("n"))).isEqualTo(42L);
        assertThat(num(withOos.get("outOfSpec"))).as("超规值必须如实计数").isEqualTo(14L);
        assertThat(dec(withOos.get("cpk")).doubleValue())
                .as("掺进超规值后 Cpk 必须变差")
                .isLessThan(dec(cap.get("cpk")).doubleValue());

        // ---- 候选清单 ----
        List<Map<String, Object>> items = dataList("/api/stat/capability/items");
        assertThat(items).anySatisfy(r -> {
            assertThat(num(r.get("productId"))).isEqualTo(capProductId);
            assertThat(num(r.get("itemId"))).isEqualTo(1L);
            assertThat(num(r.get("sampleCount"))).isEqualTo(42L);
        });

        // 没有任何实测值的组合要给清楚的提示，而不是返回一堆 null
        assertThat(num(get("/api/stat/capability?productId=999999&itemId=999999").get("code")))
                .as("查不到数据要报业务错误").isNotZero();
        assertThat(num(get("/api/stat/capability?productId=999999&itemId=999999&from=2026-13-01")
                .get("code"))).as("日期格式错也要拦").isNotZero();

        // 漏参数要给人话。交给 Spring 的 required=true 会抛
        // 「Required request parameter 'itemId' ... is not present」，原样弹给用户就是英文天书
        Map<String, Object> missing = get("/api/stat/capability?productId=1");
        assertThat(num(missing.get("code"))).as("缺 itemId 要拦").isNotZero();
        assertThat(String.valueOf(missing.get("msg")))
                .as("提示里要出现中文的「检验项」，不能是 Spring 的英文异常")
                .contains("检验项")
                .doesNotContain("is not present");
    }

    @Test
    @Order(21)
    @DisplayName("质量月报：按月聚合、分组能汇总回总量、空月不假警报、非法月份给中文提示")
    void monthlyQualityReport() {
        String thisMonth = YearMonth.now().toString();
        String lastMonth = YearMonth.now().minusMonths(1).toString();

        // ---- 不传月份默认上月：月报都在月头上看，默认当月是一屏「还没做完」 ----
        Map<String, Object> def = data(get("/api/reports/monthly"));
        assertThat(def.get("month")).isEqualTo(lastMonth);

        // ---- 上月一张单都没有：必须说「没有检验」，不能给 0% ----
        Map<String, Object> empty = data(get("/api/reports/monthly?month=" + lastMonth));
        assertThat(empty.get("hasData")).isEqualTo(false);
        assertThat(asMap(empty.get("current")).get("yieldRate"))
                .as("空月的合格率必须是 null（页面显示「-」），不是 0")
                .isNull();
        assertThat(asMap(empty.get("delta")).get("yieldPp"))
                .as("上上月同样没数据，不给环比").isNull();
        List<?> emptyNarr = (List<?>) empty.get("narrative");
        assertThat(emptyNarr).as("结论至少要有一句").isNotEmpty();
        assertThat(String.valueOf(emptyNarr.get(0)))
                .contains("没有任何已提交的检验单")
                .as("空月不能输出 0.0% 这种看着像真事的假警报")
                .doesNotContain("0.0%");

        // ---- 本月：先取快照，再交一张已知 14 件的单，验增量 ----
        Map<String, Object> before = asMap(data(get("/api/reports/monthly?month=" + thisMonth)).get("current"));
        Long b = capBatch("IT-MONTH-1");
        Long id = num(data(post("/api/inspections", Map.of("batchId", b))).get("inspectionId"));
        Map<String, Object> d = detail(id);
        assertThat(rows(d)).as("固定抽 14 件").hasSize(14);
        data(post("/api/inspections/" + id + "/submit", Map.of("lines", capLines(d, "15.0", "15.0"))));

        Map<String, Object> month = data(get("/api/reports/monthly?month=" + thisMonth));
        Map<String, Object> cur = asMap(month.get("current"));
        assertThat(month.get("hasData")).isEqualTo(true);
        assertThat(num(cur.get("orders")) - num(before.get("orders"))).as("多一张单").isEqualTo(1L);
        assertThat(num(cur.get("pieceTotal")) - num(before.get("pieceTotal"))).as("多受检 14 件").isEqualTo(14L);
        assertThat(num(cur.get("piecePass")) - num(before.get("piecePass"))).as("14 件全合格").isEqualTo(14L);

        // 分组汇总必须等于总量，否则说明有行落在分组之外（比如 inspect_type 为空的行被漏掉）
        long sumOrders = asList(month.get("byType")).stream().mapToLong(o -> num(asMap(o).get("orders"))).sum();
        assertThat(sumOrders).as("按类型分组的单数之和要等于总单数").isEqualTo(num(cur.get("orders")));
        long sumPieces = asList(month.get("byType")).stream().mapToLong(o -> num(asMap(o).get("pieceTotal"))).sum();
        assertThat(sumPieces).as("按类型分组的受检件之和要等于总受检件").isEqualTo(num(cur.get("pieceTotal")));
        assertThat(num(cur.get("piecePass")) + num(cur.get("pieceFail")))
                .as("合格件 + 不合格件必须等于受检件，分子分母不能对不上")
                .isEqualTo(num(cur.get("pieceTotal")));

        // 单数、受检件、合格率都要能在结论里读到，不能只给一堆裸数字
        List<?> narr = (List<?>) month.get("narrative");
        assertThat(String.valueOf(narr.get(0)))
                .contains(String.valueOf(month.get("label")))
                .contains("%");

        // ---- 非法月份：给中文提示，不吐 DateTimeParseException ----
        Map<String, Object> bad = get("/api/reports/monthly?month=2026-13");
        assertThat(num(bad.get("code"))).as("非法月份要拦").isNotZero();
        assertThat(String.valueOf(bad.get("msg")))
                .contains("yyyy-MM")
                .doesNotContain("DateTimeParseException");
    }

    @Test
    @Order(22)
    @DisplayName("供应商主数据：存量文本自动建档、重名与越权拦截、批次挂档取名称快照、统计按档归并")
    void supplierMasterData() {
        // ---- V12 迁移：V1 种子里那条批次的自由文本应已建成档案并回填 ----
        List<Map<String, Object>> list = asList(get("/api/suppliers").get("data"));
        assertThat(list).as("V12 应把批次上的存量文本刷成档案").isNotEmpty();
        Map<String, Object> seeded = list.stream()
                .filter(r -> "宁波精铸一厂".equals(r.get("supplier_name")))
                .findFirst().orElse(null);
        assertThat(seeded).as("种子数据里的供应商必须出现在档案里").isNotNull();
        assertThat(String.valueOf(seeded.get("supplier_code")))
                .as("整行内容: " + seeded).startsWith("SUP");
        assertThat(num(seeded.get("batches")))
                .as("V12 应把种子批次的 supplier_id 回填上").isGreaterThanOrEqualTo(1L);

        // ---- 新建：编码自动生成，默认在用 ----
        Map<String, Object> created = data(post("/api/suppliers", Map.of(
                "supplierName", "IT 测试供应商甲",
                "shortName", "测试甲",
                "category", "MATERIAL",
                "level", "B",
                "contact", "张三",
                "phone", "13800000000")));
        Long supId = num(created.get("id"));
        assertThat(supId).isNotNull();
        String code = String.valueOf(created.get("supplierCode"));
        assertThat(code).startsWith("SUP");
        assertThat(created.get("status")).isEqualTo("ACTIVE");
        assertThat(created.get("level")).isEqualTo("B");

        // ---- 重名：同一家厂只建一条档，否则统计口径又会裂开 ----
        Map<String, Object> dup = post("/api/suppliers", Map.of("supplierName", "IT 测试供应商甲"));
        assertThat(num(dup.get("code"))).as("同名供应商要拦").isNotZero();
        assertThat(String.valueOf(dup.get("msg"))).contains("已存在");

        // ---- 评级只认 A/B/C：空不等于 C，但也不能塞个 D 进来 ----
        Map<String, Object> badLevel = post("/api/suppliers",
                Map.of("supplierName", "IT 测试供应商乙", "level", "D"));
        assertThat(num(badLevel.get("code"))).isNotZero();
        assertThat(String.valueOf(badLevel.get("msg"))).contains("A / B / C");

        // ---- 建档只给管理员：检验员能建批次，但不能自己造供应商 ----
        String adminToken = token;
        token = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        try {
            assertThat(num(post("/api/suppliers", Map.of("supplierName", "IT 越权供应商")).get("code")))
                    .as("供应商建档限管理员").isNotZero();
            assertThat(asList(get("/api/suppliers").get("data")))
                    .as("读取对检验员开放：建批次要选供应商").isNotEmpty();
        } finally {
            token = adminToken;
        }

        // ---- 批次挂档：名称取主数据快照，不接受前端传来的名字 ----
        Map<String, Object> b1 = data(post("/api/batches", Map.of(
                "batchNo", "IT-SUP-B1", "productId", capProductId, "qty", 5,
                "inspectType", "IQC", "supplierId", supId,
                "supplier", "前端乱传的名字应当被覆盖")));
        assertThat(b1.get("supplier")).as("名称快照取自主数据").isEqualTo("IT 测试供应商甲");
        assertThat(num(b1.get("supplierId"))).isEqualTo(supId);

        Long b3 = num(data(post("/api/batches", Map.of(
                "batchNo", "IT-SUP-B3", "productId", capProductId, "qty", 5,
                "inspectType", "IQC", "supplierId", supId))).get("id"));
        assertThat(b3).isNotNull();

        // ---- 只填名称也能自动挂档（按全称精确匹配），省得老习惯的人每次都选 ----
        data(post("/api/batches", Map.of(
                "batchNo", "IT-SUP-B4", "productId", capProductId, "qty", 5,
                "inspectType", "IQC", "supplier", "IT 测试供应商甲")));
        Map<String, Object> b4 = asList(get("/api/batches?keyword=IT-SUP-B4").get("data")).get(0);
        assertThat(num(b4.get("supplierId")))
                .as("手填的全称能对上档案名的，应自动挂上 supplier_id").isEqualTo(supId);

        // ---- 统计按档案归并：同一家厂的两个批次只能出一行 ----
        List<Map<String, Object>> stat = asList(get("/api/batches/stat/supplier").get("data"));
        Map<String, Object> row = stat.stream()
                .filter(r -> "IT 测试供应商甲".equals(r.get("supplier")))
                .findFirst().orElse(null);
        assertThat(row).as("同一供应商的多个批次必须归并成一行").isNotNull();
        assertThat(num(row.get("batches"))).as("至少 B1 + B3 + B4 三个批次").isGreaterThanOrEqualTo(3L);
        assertThat(num(row.get("unlinked"))).as("挂了档案的批次不算未建档").isZero();

        // ---- 停用：下拉里消失，且不能再建批次 ----
        data(post("/api/suppliers/" + supId + "/status?status=DISABLED", Map.of()));
        assertThat(asList(get("/api/suppliers/options").get("data")))
                .as("停用的供应商不该出现在新建批次的下拉里")
                .noneMatch(o -> supId.equals(num(o.get("id"))));

        Map<String, Object> disabledBatch = post("/api/batches", Map.of(
                "batchNo", "IT-SUP-B5", "productId", capProductId, "qty", 5, "supplierId", supId));
        assertThat(num(disabledBatch.get("code"))).as("停用供应商不能再建批次").isNotZero();
        assertThat(String.valueOf(disabledBatch.get("msg"))).contains("已停用");

        // 停用不删档：历史批次照样能统计、能追溯
        assertThat(asList(get("/api/batches/stat/supplier").get("data")))
                .as("停用后历史批次仍要在统计里")
                .anyMatch(r -> "IT 测试供应商甲".equals(r.get("supplier")));

        data(post("/api/suppliers/" + supId + "/status?status=ACTIVE", Map.of()));
        assertThat(asList(get("/api/suppliers/options").get("data")))
                .anyMatch(o -> supId.equals(num(o.get("id"))));

        // ---- 关键字过滤 ----
        // 中文关键字直接拼进 URL，交给 TestRestTemplate 自己编码：
        // 先 URLEncoder 再交给它会被二次编码成 %25E6...，服务端拿到乱码，搜出空列表
        assertThat(asList(get("/api/suppliers?keyword=测试甲").get("data")))
                .as("简称也要能搜到").anyMatch(r -> supId.equals(num(r.get("id"))));
        assertThat(asList(get("/api/suppliers?keyword=" + code).get("data")))
                .as("编码也要能搜到").anyMatch(r -> supId.equals(num(r.get("id"))));
    }

    @Test
    @Order(23)
    @DisplayName("检验员分析：合计等于各行之和、草稿不计、单量不够不告警、检验员无权查看")
    void inspectorStats() {
        // ---- 先以检验员身份录一张单，制造第二个「人」的维度 ----
        // 管理员自己也录过单（前面几十例都在用 admin），两个人才能验组间对比的边界
        String adminToken = token;
        token = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        Long inspBatch = capBatch("IT-INS-1");
        Long inspId = num(data(post("/api/inspections", Map.of("batchId", inspBatch))).get("inspectionId"));
        data(post("/api/inspections/" + inspId + "/submit",
                Map.of("lines", capLines(detail(inspId), "15.00", "15.01"))));
        token = adminToken;

        Map<String, Object> r = data(get("/api/stat/inspectors"));
        assertThat(num(r.get("days"))).as("默认回看 90 天").isEqualTo(90L);
        assertThat(String.valueOf(r.get("from"))).isNotEmpty();
        assertThat(String.valueOf(r.get("to"))).isEqualTo(String.valueOf(LocalDate.now()));

        List<Map<String, Object>> rows = asList(r.get("rows"));
        assertThat(rows).as("至少要出现集成检验员这一行").isNotEmpty();

        Map<String, Object> inspector = rows.stream()
                .filter(x -> "集成检验员".equals(x.get("inspector")))
                .findFirst().orElse(null);
        assertThat(inspector).as("单据的检验员名取自登录态展示名，实际行: " + rows).isNotNull();
        assertThat(num(inspector.get("orders"))).isGreaterThanOrEqualTo(1L);
        assertThat(num(inspector.get("pieceTotal"))).isGreaterThanOrEqualTo(14L);
        assertThat(num(inspector.get("rowTotal"))).as("14 件 × 1 项").isGreaterThanOrEqualTo(14L);
        // 1 单零不良不构成信号：这里锁的是「单量门槛」，不是「有没有不良」
        assertThat(asList(inspector.get("flags")))
                .as("单量不够时不该弹零不良提示，实际: " + inspector.get("flags")).isEmpty();
        assertThat(inspector.get("level")).isEqualTo("OK");
        // 每行秒数、每单分钟数都要给出来（哪怕为 0），前端才不至于显示 NaN
        assertThat(inspector.get("secPerRow")).isNotNull();

        // ---- 合计行必须等于各行之和 ----
        Map<String, Object> total = asMap(r.get("total"));
        long sumOrders = rows.stream().mapToLong(x -> num(x.get("orders"))).sum();
        long sumPieces = rows.stream().mapToLong(x -> num(x.get("pieceTotal"))).sum();
        assertThat(num(total.get("orders"))).as("合计单量 = 各行之和").isEqualTo(sumOrders);
        assertThat(num(total.get("pieceTotal"))).as("合计件数 = 各行之和").isEqualTo(sumPieces);
        assertThat(num(total.get("piecePass")) + num(total.get("pieceFail")))
                .as("合格件 + 不合格件 = 受检件")
                .isEqualTo(num(total.get("pieceTotal")));

        // ---- 草稿不计入：谁正在录单不该影响统计 ----
        long beforeOrders = num(total.get("orders"));
        Long draftBatch = capBatch("IT-INS-DRAFT");
        Long draftId = num(data(post("/api/inspections", Map.of("batchId", draftBatch))).get("inspectionId"));
        data(post("/api/inspections/" + draftId + "/save",
                Map.of("lines", capLines(detail(draftId), "15.00", "15.00"))));
        assertThat(num(asMap(data(get("/api/stat/inspectors")).get("total")).get("orders")))
                .as("草稿单不能进统计").isEqualTo(beforeOrders);

        // ---- 权限：检验员看不到这一页 ----
        // 不是怕他看自己的数，是这页的用法是「主管决定抽查谁」，开着就变成互相打分
        token = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        try {
            assertThat(statusOf("/api/stat/inspectors")).as("检验员无权查看").isEqualTo(403);
        } finally {
            token = adminToken;
        }

        // ---- 参数错误给中文 ----
        Map<String, Object> badDate = get("/api/stat/inspectors?from=2026-13-01");
        assertThat(num(badDate.get("code"))).isNotZero();
        assertThat(String.valueOf(badDate.get("msg")))
                .contains("yyyy-MM-dd")
                .doesNotContain("DateTimeParseException");

        assertThat(num(get("/api/stat/inspectors?from=2026-10-10&to=2026-10-01").get("code")))
                .as("开始晚于结束要拦").isNotZero();
        assertThat(String.valueOf(get("/api/stat/inspectors?from=2020-01-01&to=2026-10-01").get("msg")))
                .as("跨度超两年要拦，并说清为什么").contains("730");
    }

    @Test
    @Order(24)
    @DisplayName("待检批次：当天建批不报、超期点名、出货未检升最高级、作废后批次退回待检")
    void pendingBatchAlert() {
        long basePending = num(data(get("/api/todo/summary")).get("pendingBatch"));
        long baseActionable = num(data(get("/api/todo/summary")).get("actionable"));

        // ---- 造三个批次：今天建 / 3 天前到货 / 10 天前到货 ----
        // 都用过程能力那个产品，它有 ACTIVE 标准，能真的开出检验单
        Long warmId = capBatch("IT-PB-WARM");
        data(post("/api/batches", Map.of(
                "batchNo", "IT-PB-MID", "productId", capProductId, "qty", 10,
                "arrivedAt", LocalDate.now().minusDays(3).atTime(9, 0).toString())));
        Long oldId = num(data(post("/api/batches", Map.of(
                "batchNo", "IT-PB-OLD", "productId", capProductId, "qty", 10,
                "arrivedAt", LocalDate.now().minusDays(10).atTime(9, 0).toString()))).get("id"));

        // ---- 当天建批不报警：下午到货、明天再检是正常节奏 ----
        Map<String, Object> warm = pendingRow("IT-PB-WARM");
        assertThat(warm).as("当天建批也要出现在列表里，只是不标红").isNotNull();
        assertThat(warm.get("level")).isEqualTo("OK");
        assertThat(warm.get("urgent")).isEqualTo(false);
        assertThat(num(warm.get("idleDays"))).isZero();

        // ---- 超期按停留天数判，天数从到货时间算 ----
        Map<String, Object> mid = pendingRow("IT-PB-MID");
        assertThat(mid.get("level")).as("3 天前到货仍未开单，默认 2 天阈值下算超期").isEqualTo("OVERDUE");
        assertThat(num(mid.get("idleDays"))).isEqualTo(3L);

        Map<String, Object> old = pendingRow("IT-PB-OLD");
        assertThat(num(old.get("idleDays"))).as("应按到货时间算，不是建批时间").isEqualTo(10L);

        // ---- 角标只加真正需要动作的：WARM 是 OK，不算 ----
        Map<String, Object> summary = data(get("/api/todo/summary"));
        assertThat(num(summary.get("pendingBatch")) - basePending)
                .as("超期两个各占一个待办，当天建的那个不占").isEqualTo(2L);
        assertThat(num(summary.get("actionable")) - baseActionable)
                .as("漏检批次必须计入总角标，否则这功能等于没人看得见").isEqualTo(2L);

        // ---- 阈值可调：放宽到 30 天，3 天前那个就只是「即将超期」 ----
        Map<String, Object> midLoose = pendingRow("IT-PB-MID", "?days=30");
        assertThat(midLoose.get("level")).isEqualTo("DUE");
        // 越界的值不能炸，夹紧后照常返回
        assertThat(pendingRow("IT-PB-MID", "?days=-1")).isNotNull();
        assertThat(pendingRow("IT-PB-MID", "?days=99999")).isNotNull();

        // ---- 已出货未检：升到最高级，且排在所有普通超期之前 ----
        data(post("/api/shipments", Map.of(
                "batchId", oldId, "customer", "IT 待检客户", "qty", 3, "force", true)));
        Map<String, Object> shipped = pendingRow("IT-PB-OLD");
        assertThat(shipped.get("level")).as("发了货却没有检验结论，比几天没检严重得多")
                .isEqualTo("SHIPPED");
        assertThat(String.join(" ", strings(shipped.get("flags")))).contains("已出货");
        // 已经出货的批次不该被调大的阈值「洗白」
        assertThat(pendingRow("IT-PB-OLD", "?days=90").get("level")).isEqualTo("SHIPPED");

        List<Map<String, Object>> list = dataList("/api/todo/batches");
        assertThat(indexOfBatch(list, "IT-PB-OLD"))
                .as("出货未检要排在最前面").isLessThan(indexOfBatch(list, "IT-PB-MID"));

        // ---- 开了单就不再算漏检 ----
        Long warmIns = num(data(post("/api/inspections",
                Map.of("batchId", warmId, "sampleQty", 1))).get("inspectionId"));
        assertThat(batchStatus(warmId)).isEqualTo("INSPECTING");
        assertThat(pendingRow("IT-PB-WARM")).as("已经有人开单了，不该还挂在漏检里").isNull();

        // ---- 作废之后批次要退回待检，不能永远挂在「在检」 ----
        data(post("/api/inspections/" + warmIns + "/void?reason=IT 作废后批次应退回待检", Map.of()));
        assertThat(batchStatus(warmId))
                .as("作废是唯一会让批次「无单可检」的动作，状态必须跟着回退").isEqualTo("PENDING");

        Map<String, Object> back = pendingRow("IT-PB-WARM");
        assertThat(back).as("退回待检后要重新出现在漏检提醒里").isNotNull();
        assertThat(num(back.get("voidLots"))).isEqualTo(1L);
        assertThat(String.join(" ", strings(back.get("flags"))))
                .as("要明确说是「作废了」而不是「没检过」，两种情况的处理方式不同")
                .contains("作废");
    }

    @Test
    @Order(25)
    @DisplayName("备份健康：没记录不假装正常、按小时算新鲜度、失败压过新鲜的成功、非管理员看不到")
    void backupHealthVisible() {
        // ---- 表刚建好、脚本还没跑过 ----
        // 最危险的「看起来正常」：空表被当成「0 小时前刚备份成功」。
        // 用户会以为有备份，实际一份都没有
        backupLogMapper.delete(null);
        Map<String, Object> none = data(get("/api/system/backup-health"));
        assertThat(none.get("level")).as("没有记录就说没有记录").isEqualTo("NO_RECORD");
        assertThat(none.get("alert")).as("刚接上脚本不该红，但也不能绿").isEqualTo(false);
        assertThat(none.get("hoursSinceSuccess")).isNull();
        assertThat(strings(none.get("flags")).get(0)).contains("还没有任何备份执行记录");

        // ---- 脚本写入一条成功记录 ----
        insertBackup("SUCCESS", 3 * 1024L * 1024L, 0, "备份完成 qcinspect-x.sql.gz（3072 KB），清理过期 0 个");
        Map<String, Object> ok = data(get("/api/system/backup-health"));
        assertThat(ok.get("level")).isEqualTo("OK");
        assertThat(num(ok.get("hoursSinceSuccess"))).isZero();
        assertThat(num(ok.get("runs30d"))).isEqualTo(1L);
        assertThat(num(ok.get("failures30d"))).isZero();
        assertThat(((Number) ok.get("lastSuccessMb")).doubleValue())
                .as("3072 KB 要显示成 3.0 MB：KB 量级看不出「备份是不是突然小了一个数量级」")
                .isEqualTo(3.0);
        assertThat(ok.get("lastSuccessSizeText")).isEqualTo("3.0 MB");
        assertThat(asList(ok.get("recent"))).as("一次请求就带回最近几次，页面不用再点一下").hasSize(1);
        assertThat(asMap(asList(ok.get("recent")).get(0)).get("status")).isEqualTo("SUCCESS");
        // 越界的条数不能炸，夹紧后照常返回
        assertThat(asList(data(get("/api/system/backup-health?recent=9999")).get("recent"))).hasSize(1);

        // ---- 空库备份只有十几 KB：按 MB 报会变成「0.0 MB」，页面上看着像没备份成 ----
        backupLogMapper.delete(null);
        insertBackup("SUCCESS", 14812L, 0, null);
        assertThat(data(get("/api/system/backup-health")).get("lastSuccessSizeText"))
                .as("小文件按 KB 报").isEqualTo("14 KB");

        // ---- 40 小时没有成功记录：漏了一轮，报警但还不是故障 ----
        backupLogMapper.delete(null);
        insertBackup("SUCCESS", 2 * 1024L * 1024L, 40, null);
        Map<String, Object> stale = data(get("/api/system/backup-health"));
        assertThat(stale.get("level")).isEqualTo("STALE");
        assertThat(stale.get("alert")).isEqualTo(true);

        // ---- 100 小时：连漏多轮，按故障处理 ----
        backupLogMapper.delete(null);
        insertBackup("SUCCESS", 2 * 1024L * 1024L, 100, null);
        assertThat(data(get("/api/system/backup-health")).get("level")).isEqualTo("OVERDUE");

        // ---- 失败优先于「上次成功还很新」 ----
        // 今天凌晨成功了、下午手动跑一次失败：数据安全，但脚本已经坏了。
        // 不报的话明天凌晨就是真断，所以照样要亮灯
        backupLogMapper.delete(null);
        insertBackup("SUCCESS", 2 * 1024L * 1024L, 5, null);
        insertBackup("FAILED", null, 0, "pg_dump 失败");
        Map<String, Object> failed = data(get("/api/system/backup-health"));
        assertThat(failed.get("level")).isEqualTo("FAILED");
        assertThat(failed.get("lastMessage")).isEqualTo("pg_dump 失败");
        String failedText = String.join(" ", strings(failed.get("flags")));
        assertThat(failedText)
                .as("失败文案要说明现有备份还能用，否则会把人吓成「数据没了」")
                .contains("还能用");
        assertThat(failedText).as("失败原因要摆在显眼处，不能只留在表格里").contains("pg_dump");

        // ---- 权限：检验员看不到 ----
        // 不是怕他知道，是他什么也做不了，只会在心里多一条关不掉的灯
        String adminToken = token;
        token = (String) data(post("/api/auth/login",
                Map.of("username", "it_inspector", "password", "it123456"))).get("token");
        try {
            assertThat(statusOf("/api/system/backup-health")).as("检验员无权查看").isEqualTo(403);
        } finally {
            token = adminToken;
        }

        // 收尾：把测试造出来的备份记录清掉，后面的用例不该看见这些
        backupLogMapper.delete(null);
    }

    @Test
    @Order(26)
    @DisplayName("批次进度：草稿算抽检不算已判、复检算第二单、作废单必须从进度里消失")
    void batchProgress() {
        // ---- 一单都没开：给 0 不给 null ----
        // null 到前端要先判空，判漏一处就是 NaN% 的进度条
        aqlBatch("IT-PROG-EMPTY", 100);
        Map<String, Object> empty = batchRow("IT-PROG-EMPTY");
        assertThat(num(empty.get("lotCount"))).as("没开单也要有 0，前端才能直接画").isZero();
        assertThat(num(empty.get("sampledQty"))).isZero();
        assertThat(num(empty.get("doneQty"))).isZero();

        // ---- 建一张草稿单：抽检件数已经算上，已判件数还是 0 ----
        Long batch = aqlBatch("IT-PROG-MAIN", 300);
        Long ins1 = num(data(post("/api/inspections", Map.of("batchId", batch))).get("inspectionId"));
        int sample = num(asMap(detail(ins1).get("inspection")).get("sampleQty")).intValue();

        Map<String, Object> draft = batchRow("IT-PROG-MAIN");
        assertThat(num(draft.get("lotCount"))).isEqualTo(1L);
        assertThat(num(draft.get("sampledQty")))
                .as("抽检件数是建单时定下的，草稿阶段就该看见").isEqualTo(sample);
        assertThat(num(draft.get("doneQty")))
                .as("件数要等判定完才回写，草稿阶段是 0").isZero();

        // ---- 提交：件数回写，进度走到头 ----
        data(post("/api/inspections/" + ins1 + "/submit", Map.of("lines", fillCompliant(detail(ins1)))));
        Map<String, Object> judged = batchRow("IT-PROG-MAIN");
        assertThat(num(judged.get("doneQty"))).as("提交后件数回写").isEqualTo(sample);
        assertThat(num(judged.get("passedQty"))).isEqualTo(sample);
        assertThat(num(judged.get("failedQty"))).isZero();

        // ---- 返工复检再开一单：算第二单，抽检件数累加 ----
        Long ins2 = num(data(post("/api/inspections", Map.of("batchId", batch))).get("inspectionId"));
        Map<String, Object> twice = batchRow("IT-PROG-MAIN");
        assertThat(num(twice.get("lotCount"))).as("复检是第二张单，不能把批次数减成负数").isEqualTo(2L);
        assertThat(num(twice.get("sampledQty"))).isEqualTo(sample * 2L);

        // ---- 作废第二单：进度必须退回去 ----
        // 作废意味着这张单的成绩不算数。留着它，页面上的「已检」会永远比实际多，
        // 而且越作废越对不上，最后没人再信这个数字
        data(post("/api/inspections/" + ins2 + "/void?reason=IT 作废后进度应退回", Map.of()));
        Map<String, Object> afterVoid = batchRow("IT-PROG-MAIN");
        assertThat(num(afterVoid.get("lotCount"))).isEqualTo(1L);
        assertThat(num(afterVoid.get("sampledQty"))).as("作废单不许留在进度里").isEqualTo(sample);
        assertThat(num(afterVoid.get("doneQty"))).isEqualTo(sample);
    }

    @Test
    @Order(27)
    @DisplayName("标准改版：publish 出来必须是草稿（否则改不了项）、对比能判出放宽还是收紧")
    void standardVersionDiff() {
        QcProduct p = new QcProduct();
        p.setProductCode("IT-DIFF");
        p.setProductName("标准改版试用件");
        p.setUnit("件");
        productMapper.insert(p);
        Long productId = p.getId();

        // ---- v1：内径 11.95 ~ 12.05 ----
        Long v1 = num(data(post("/api/standards", Map.of(
                "productId", productId, "standardCode", "S-IT-DIFF",
                "samplingType", "FULL", "sampleSize", 1, "allowedNgCount", 0))).get("id"));
        assertThat(num(post("/api/standards/" + v1 + "/items",
                List.of(diffItem("11.95", "12.05"))).get("code"))).isZero();
        assertThat(num(post("/api/standards/" + v1 + "/activate", Map.of()).get("code"))).isZero();

        // ---- 第一个版本没有更早的可比 ----
        Map<String, Object> first = data(get("/api/standards/" + v1 + "/diff"));
        assertThat(first.get("firstVersion")).isEqualTo(true);
        assertThat(asList(first.get("changes"))).isEmpty();

        // ---- 发布新版本：必须是草稿 ----
        // 早先这里直接建成 ACTIVE，而 saveItems 只放行 DRAFT，
        // 结果「发布新版本」只多了一个版本号，限值一项也改不了
        Map<String, Object> v2std = data(post("/api/standards/" + v1 + "/publish", Map.of()));
        assertThat(v2std.get("status"))
                .as("发布新版本的目的就是改东西，所以必须是草稿").isEqualTo("DRAFT");
        assertThat(num(v2std.get("version"))).isEqualTo(2L);
        Long v2 = num(v2std.get("id"));

        // ---- 还没改任何项：两版口径一致 ----
        Map<String, Object> same = data(get("/api/standards/" + v2 + "/diff"));
        assertThat(same.get("verdict")).isEqualTo("NEUTRAL");
        assertThat(num(asMap(same.get("from")).get("version"))).isEqualTo(1L);

        // ---- 草稿能改：上限 12.05 → 12.10 = 放宽 ----
        assertThat(num(post("/api/standards/" + v2 + "/items",
                List.of(diffItem("11.95", "12.10"))).get("code")))
                .as("草稿必须能改项，否则改版这条路是断的").isZero();
        assertThat(num(post("/api/standards/" + v2 + "/activate", Map.of()).get("code"))).isZero();

        Map<String, Object> diff = data(get("/api/standards/" + v2 + "/diff"));
        assertThat(diff.get("verdict")).as("上限调高就是放宽").isEqualTo("RELAXED");
        assertThat(diff.get("verdictText")).isEqualTo("整体放宽");
        String summary = String.join(" ", strings(diff.get("summary")));
        assertThat(summary).contains("上限").contains("放宽");
        assertThat(String.valueOf(diff.get("advice")))
                .as("提示要直接指向排查方向").contains("优先查这一版");

        // ---- 指定 against 也能比 ----
        assertThat(data(get("/api/standards/" + v2 + "/diff?against=" + v1)).get("verdict"))
                .isEqualTo("RELAXED");

        // ---- 对比一个不存在的版本要报错，不能静默当成无差异 ----
        assertThat(num(get("/api/standards/" + v2 + "/diff?against=99999999").get("code")))
                .as("不存在的版本不能被当成「没差异」").isNotZero();
    }

    @Test
    @Order(28)
    @DisplayName("不良帕累托：参数全空也要能跑（线上就是这么崩的）、占比与累计占比自洽")
    void defectParetoNullableParams() {
        // 线上 500 的真实成因：`#{productId} IS NULL` 没写 CAST，PG 推不出 $1 的类型
        //    ERROR: could not determine data type of parameter $1
        // 前端打开不良处置页时只传 from、不传 productId，正好走 productId=null 这条。
        // 这里把四种参数组合都打一遍——旧代码只在「两个都传」时才不崩。
        //
        // 断言用原始 code 而不是 data()：data() 自己会断言 code==0，
        // 出错时抛的是断言异常，看不出到底是哪个组合坏的。而且 asList 对 null
        // 会静默返回空表，只断言「列表不为 null」等于没断言。
        assertThat(num(get("/api/defects/pareto").get("code")))
                .as("不带任何参数时不能 500").isZero();
        assertThat(num(get("/api/defects/pareto?from=2000-01-01T00:00:00Z").get("code")))
                .as("只传 from（前端的真实调用）不能 500").isZero();
        assertThat(num(get("/api/defects/pareto?productId=99999999").get("code")))
                .as("只传 productId 不能 500").isZero();
        assertThat(num(get("/api/defects/pareto?productId=99999999&from=2000-01-01T00:00:00Z").get("code")))
                .as("两个都传也不能 500").isZero();

        // 筛选条件要真的生效，否则「不崩」只是把错数据当对数据
        assertThat(asList(get("/api/defects/pareto?productId=99999999").get("data")))
                .as("不存在的产品应当筛出空表").isEmpty();

        // ---- 有数据时，口径要自洽 ----
        List<Map<String, Object>> all = asList(get("/api/defects/pareto").get("data"));
        assertThat(all).as("前面的用例判过 NG，帕累托不该是空的").isNotEmpty();
        double lastCumulative = 0;
        long prevQty = Long.MAX_VALUE;
        for (Map<String, Object> row : all) {
            long qty = num(row.get("qty")).longValue();
            double percent = ((Number) row.get("percent")).doubleValue();
            double cumulative = ((Number) row.get("cumulative_percent")).doubleValue();
            assertThat(qty).as("qty 必须降序").isLessThanOrEqualTo(prevQty);
            prevQty = qty;
            assertThat(percent).as("单条占比不能超过 100%").isBetween(0.0, 100.0);
            assertThat(cumulative).as("累计占比只能升不能降").isGreaterThanOrEqualTo(lastCumulative);
            lastCumulative = cumulative;
        }
        assertThat(lastCumulative).as("累计占比到最后必须收敛到 100%")
                .isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @Order(29)
    @DisplayName("检验单详情带标准上下文：标准改版后，老单说得出「后来放宽了」，快照却不动")
    void inspectionStandardContext() {
        QcProduct p = new QcProduct();
        p.setProductCode("IT-STDCTX");
        p.setProductName("标准上下文试用件");
        p.setUnit("件");
        productMapper.insert(p);
        Long productId = p.getId();

        // ---- v1：内径 11.95 ~ 12.05 ----
        Long v1 = num(data(post("/api/standards", Map.of(
                "productId", productId, "standardCode", "S-IT-CTX",
                "samplingType", "FULL", "sampleSize", 1, "allowedNgCount", 0))).get("id"));
        assertThat(num(post("/api/standards/" + v1 + "/items",
                List.of(diffItem("11.95", "12.05"))).get("code"))).isZero();
        assertThat(num(post("/api/standards/" + v1 + "/activate", Map.of()).get("code"))).isZero();

        Long id = num(data(post("/api/inspections", Map.of("batchId", ctxBatch("IT-CTX-B1", productId))))
                .get("inspectionId"));
        Map<String, Object> d = detail(id);
        assertThat(num(asMap(d.get("inspection")).get("standardVersion"))).isEqualTo(1L);
        data(post("/api/inspections/" + id + "/submit", Map.of("lines", fillCompliant(d))));

        // ---- 还没改版：上下文要说「仍是现行版本」，而不是什么都不给 ----
        Map<String, Object> ctx = asMap(detail(id).get("standardContext"));
        assertThat(ctx).as("详情必须带标准上下文").isNotEmpty();
        assertThat(ctx.get("changed")).isEqualTo(false);
        assertThat(num(ctx.get("usedVersion"))).isEqualTo(1L);
        assertThat(num(ctx.get("currentVersion"))).isEqualTo(1L);
        assertThat(num(ctx.get("versionsSince"))).as("没改版就是 0 版差").isZero();
        assertThat(String.valueOf(ctx.get("note"))).contains("仍是现行版本");

        // ---- 改版：上限 12.05 → 12.20（放宽） ----
        Long v2 = num(data(post("/api/standards/" + v1 + "/publish", Map.of())).get("id"));
        assertThat(num(post("/api/standards/" + v2 + "/items",
                List.of(diffItem("11.95", "12.20"))).get("code"))).isZero();
        assertThat(num(post("/api/standards/" + v2 + "/activate", Map.of()).get("code"))).isZero();

        // ---- 老单：结论与快照都不动，但要能说出「标准后来改过、是放宽」 ----
        Map<String, Object> after = detail(id);
        Map<String, Object> ctx2 = asMap(after.get("standardContext"));
        assertThat(ctx2.get("changed")).as("改过版必须标出来").isEqualTo(true);
        assertThat(num(ctx2.get("usedVersion"))).as("仍是当初那版").isEqualTo(1L);
        assertThat(num(ctx2.get("currentVersion"))).isEqualTo(2L);
        assertThat(num(ctx2.get("versionsSince"))).isEqualTo(1L);
        assertThat(ctx2.get("verdict")).as("上限调高 = 放宽").isEqualTo("RELAXED");
        assertThat(asList(ctx2.get("summary"))).as("要能直接列出差在哪").isNotEmpty();

        assertThat(asMap(after.get("inspection")).get("result")).as("历史判定不能被改版改写").isEqualTo("PASS");
        assertThat(dec(rows(after).get(0).get("maxValue")))
                .as("明细快照必须还是当初的 12.05（项目铁律：标准改版不影响历史单）")
                .isEqualByComparingTo("12.05");

        // ---- 新开的单用新版本，与老单互不干扰 ----
        Long id2 = num(data(post("/api/inspections", Map.of("batchId", ctxBatch("IT-CTX-B2", productId))))
                .get("inspectionId"));
        Map<String, Object> d2 = detail(id2);
        assertThat(num(asMap(d2.get("inspection")).get("standardVersion"))).isEqualTo(2L);
        assertThat(asMap(d2.get("standardContext")).get("changed")).isEqualTo(false);
        assertThat(dec(rows(d2).get(0).get("maxValue"))).isEqualByComparingTo("12.20");
    }

    @Test
    @Order(30)
    @DisplayName("旧版标准单据汇总：作废版本还挂着几张判定单、判成了什么，点进去能看明细")
    void staleStandardUsage() {
        QcProduct p = new QcProduct();
        p.setProductCode("IT-STALE");
        p.setProductName("旧版标准复查件");
        p.setUnit("件");
        productMapper.insert(p);
        Long productId = p.getId();

        // ---- 先有一张「没有旧版单」的状态：此时不能报出任何旧版单据 ----
        assertThat(asList(data(get("/api/standards/stale-usage?productId=" + productId))
                .get("rows"))).as("还没开过单，没有旧版单可报").isEmpty();

        Long v1 = num(data(post("/api/standards", Map.of(
                "productId", productId, "standardCode", "S-IT-STALE",
                "samplingType", "FULL", "sampleSize", 1, "allowedNgCount", 0))).get("id"));
        assertThat(num(post("/api/standards/" + v1 + "/items",
                List.of(diffItem("11.95", "12.05"))).get("code"))).isZero();
        assertThat(num(post("/api/standards/" + v1 + "/activate", Map.of()).get("code"))).isZero();

        // 旧版下判两张：一张合格、一张不合格——汇总要能分开数
        Long idPass = num(data(post("/api/inspections",
                Map.of("batchId", ctxBatch("IT-STALE-B1", productId)))).get("inspectionId"));
        data(post("/api/inspections/" + idPass + "/submit",
                Map.of("lines", fillCompliant(detail(idPass)))));
        Long idFail = num(data(post("/api/inspections",
                Map.of("batchId", ctxBatch("IT-STALE-B2", productId)))).get("inspectionId"));
        data(post("/api/inspections/" + idFail + "/submit",
                Map.of("lines", fillViolating(detail(idFail)))));

        assertThat(asMap(detail(idFail).get("inspection")).get("result")).isEqualTo("FAIL");

        // ---- 改版并生效：v1 变 OBSOLETE，v2 上限收紧 ----
        Long v2 = num(data(post("/api/standards/" + v1 + "/publish", Map.of())).get("id"));
        assertThat(num(post("/api/standards/" + v2 + "/items",
                List.of(diffItem("11.95", "11.99"))).get("code"))).isZero();
        assertThat(num(post("/api/standards/" + v2 + "/activate", Map.of()).get("code"))).isZero();

        // 新版下再判一张合格的：它是现行版本，不该出现在「旧版」清单里
        Long idNew = num(data(post("/api/inspections",
                Map.of("batchId", ctxBatch("IT-STALE-B3", productId)))).get("inspectionId"));
        data(post("/api/inspections/" + idNew + "/submit",
                Map.of("lines", fillCompliant(detail(idNew)))));

        Map<String, Object> usage = data(get("/api/standards/stale-usage?productId=" + productId));
        List<Map<String, Object>> rows = asList(usage.get("rows"));

        assertThat(rows).as("只有作废的 v1 该出现，现行版和没开单的草稿都不算").hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(num(row.get("standard_id"))).isEqualTo(v1);
        assertThat(num(row.get("version"))).isEqualTo(1L);
        assertThat(String.valueOf(row.get("status"))).isEqualTo("OBSOLETE");
        assertThat(num(row.get("current_version"))).as("要说得出现行是第几版").isEqualTo(2L);
        assertThat(num(row.get("inspection_count"))).as("v1 下判过 2 张").isEqualTo(2L);
        assertThat(num(row.get("pass_count"))).isEqualTo(1L);
        assertThat(num(row.get("fail_count"))).isEqualTo(1L);
        assertThat(String.valueOf(row.get("product_name"))).isEqualTo("旧版标准复查件");
        assertThat(String.valueOf(row.get("last_used_date")))
                .as("要能看出这批旧版单判到哪一天").matches("\\d{4}-\\d{2}-\\d{2}");

        assertThat(num(usage.get("versionCount"))).isEqualTo(1L);
        assertThat(num(usage.get("inspectionCount"))).as("总数按行求和，不能让前端自己算").isEqualTo(2L);
        assertThat(num(usage.get("failCount"))).isEqualTo(1L);
        assertThat(String.valueOf(usage.get("note")))
                .as("有不合格的单时要提醒复核，而不是只报个数").contains("不合格");
        assertThat(usage.get("withoutStandard")).as("关联不上的早期单也要报出来，不能只说查得到的")
                .isNotNull();

        // ---- 点进来看明细：按标准版本筛检验单 ----
        List<Map<String, Object>> oldRows = asList(
                data(get("/api/inspections/page?current=1&size=50&standardId=" + v1)).get("records"));
        assertThat(oldRows).as("v1 下就是那两张，含判 FAIL 的").hasSize(2);
        assertThat(oldRows).extracting(r -> String.valueOf(r.get("result")))
                .containsExactlyInAnyOrder("PASS", "FAIL");

        List<Map<String, Object>> newRows = asList(
                data(get("/api/inspections/page?current=1&size=50&standardId=" + v2)).get("records"));
        assertThat(newRows).as("v2 下只有新判的那一张").hasSize(1);

        // 不带标准条件时是全部三张——筛选是真在过滤，不是恒真
        assertThat(asList(data(get("/api/inspections/page?current=1&size=50&productId=" + productId))
                .get("records"))).hasSize(3);

        // ---- 不带 productId 时是全库视角，本产品的行照样在里面 ----
        List<Map<String, Object>> all = asList(data(get("/api/standards/stale-usage")).get("rows"));
        assertThat(all).extracting(r -> String.valueOf(r.get("product_code")))
                .contains("IT-STALE");
    }

    /** 标准上下文用例的批次夹具：指定产品，一件，待检 */
    private Long ctxBatch(String batchNo, Long productId) {        QcBatch b = new QcBatch();
        b.setBatchNo(batchNo);
        b.setProductId(productId);
        b.setInspectType("IQC");
        b.setQty(1);
        b.setStatus("PENDING");
        batchMapper.insert(b);
        return b.getId();
    }

    /** 标准项请求体：一个数值项，限值用字符串传，跟前端提交的形状一致 */
    private Map<String, Object> diffItem(String min, String max) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("itemId", 1);
        m.put("valueMode", "SINGLE");
        m.put("pointCount", 1);
        m.put("isCritical", true);
        m.put("isRequired", true);
        m.put("isDestructive", false);
        m.put("minValue", new BigDecimal(min));
        m.put("maxValue", new BigDecimal(max));
        return m;
    }

    /** 批次列表里按批次号取整行，用来断言回填上去的进度字段 */
    private Map<String, Object> batchRow(String batchNo) {
        return dataList("/api/batches").stream()
                .filter(b -> batchNo.equals(b.get("batchNo")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("批次列表里没有 " + batchNo));
    }

    /** 模拟 scripts/backup.sh 写库的那一行 */
    private void insertBackup(String status, Long size, int hoursAgo, String message) {
        QcBackupLog log = new QcBackupLog();
        log.setStatus(status);
        log.setFileName("SUCCESS".equals(status) ? "qcinspect-it.sql.gz" : null);
        log.setFileSize(size);
        log.setPrunedFiles(0);
        log.setHost("it-host");
        log.setMessage(message);
        log.setCreatedAt(LocalDateTime.now().minusHours(hoursAgo));
        backupLogMapper.insert(log);
    }

    private Map<String, Object> pendingRow(String batchNo) {
        return pendingRow(batchNo, "");
    }

    private Map<String, Object> pendingRow(String batchNo, String query) {
        return dataList("/api/todo/batches" + query).stream()
                .filter(r -> batchNo.equals(r.get("batchNo")))
                .findFirst().orElse(null);
    }

    private int indexOfBatch(List<Map<String, Object>> list, String batchNo) {
        for (int i = 0; i < list.size(); i++) {
            if (batchNo.equals(list.get(i).get("batchNo"))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private String batchStatus(Long batchId) {
        return dataList("/api/batches").stream()
                .filter(b -> batchId.equals(num(b.get("id"))))
                .map(b -> String.valueOf(b.get("status")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object o) {
        return o instanceof List ? (List<String>) o : List.of();
    }

    /**
     * 过程能力用例夹具：专属产品 + 单数值项标准（规格 14~16，固定抽 14 件）。
     *
     * <p>用 FIXED 而不是 AQL：这里要的是「每单恒定 14 件」，批量变化会导致抽样量变化，
     * 手算的期望值就跟着飘了。
     */
    private Long capBatch(String batchNo) {
        if (capProductId == null) {
            QcProduct p = new QcProduct();
            p.setProductCode("IT-CAP");
            p.setProductName("过程能力试用件");
            p.setUnit("件");
            productMapper.insert(p);
            capProductId = p.getId();

            QcStandard std = new QcStandard();
            std.setStandardCode("S-IT-CAP");
            std.setProductId(capProductId);
            std.setVersion(1);
            std.setStatus("ACTIVE");
            std.setSamplingType("SAMPLE");
            std.setSamplingPlan("FIXED");
            std.setSampleSize(14);
            std.setAllowedNgCount(0);
            standardMapper.insert(std);

            QcStandardItem si = new QcStandardItem();
            si.setStandardId(std.getId());
            si.setItemId(1L);
            si.setSeq(10);
            si.setIsCritical(true);
            si.setIsRequired(true);
            si.setValueMode("SINGLE");
            si.setPointCount(1);
            si.setMinValue(new BigDecimal("14.00"));
            si.setMaxValue(new BigDecimal("16.00"));
            si.setIsDestructive(false);
            standardItemMapper.insert(si);
        }

        QcBatch b = new QcBatch();
        b.setBatchNo(batchNo);
        b.setProductId(capProductId);
        b.setInspectType("IPQC");
        b.setQty(100);
        b.setStatus("PENDING");
        batchMapper.insert(b);
        return b.getId();
    }

    /** 按件号奇偶交替填两个值，保证同一张单内相邻极差恒定 */
    private List<Map<String, Object>> capLines(Map<String, Object> detail, String even, String odd) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> rs = rows(detail);
        for (int i = 0; i < rs.size(); i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", num(rs.get(i).get("id")));
            line.put("values", List.of(i % 2 == 0 ? even : odd));
            lines.add(line);
        }
        return lines;
    }

    /** 业务失败要拦下并说清原因，不能悄无声息地按默认值存进去 */
    private void assertBizFail(Map<String, Object> resp, String msgFragment) {
        assertThat(num(resp.get("code"))).as("应当被拦下: %s", resp.get("msg")).isNotZero();
        assertThat(String.valueOf(resp.get("msg"))).contains(msgFragment);
    }

    /** POST 的状态码（403 的响应体不是业务 JSON，走 String 反序列化） */
    private int statusOfPost(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()), String.class)
                .getStatusCode().value();
    }

    /** 只关心 HTTP 状态码时用（403 的响应体不是业务 JSON，走反序列化容易踩坑） */
    private int statusOf(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class)
                .getStatusCode().value();
    }

    /** 从列表里取一把量具的最新状态（没有单条查询接口，列表本来就是全量） */
    private Map<String, Object> gauge(Long id) {
        return asList(get("/api/gauges").get("data")).stream()
                .filter(g -> id.equals(num(g.get("id"))))
                .findFirst().orElseThrow();
    }

    /** 复检用例夹具：固定抽样 1 件、Ac=0 的独立产品，判 NG 后正好落一条可返工的不良 */
    private Long rkBatch(String batchNo) {
        if (rkProductId == null) {
            QcProduct p = new QcProduct();
            p.setProductCode("IT-RK");
            p.setProductName("返工复检试用件");
            p.setUnit("件");
            productMapper.insert(p);
            rkProductId = p.getId();

            QcStandard std = new QcStandard();
            std.setStandardCode("S-IT-RK");
            std.setProductId(rkProductId);
            std.setVersion(1);
            std.setStatus("ACTIVE");
            std.setSamplingType("SAMPLE");
            std.setSamplingPlan("FIXED");
            std.setSampleSize(1);
            std.setAllowedNgCount(0);
            standardMapper.insert(std);

            QcStandardItem si = new QcStandardItem();
            si.setStandardId(std.getId());
            si.setItemId(1L);
            si.setSeq(10);
            si.setIsCritical(true);
            si.setIsRequired(true);
            si.setValueMode("SINGLE");
            si.setPointCount(1);
            si.setMinValue(new BigDecimal("11.95"));
            si.setMaxValue(new BigDecimal("12.05"));
            si.setIsDestructive(false);
            standardItemMapper.insert(si);
        }

        QcBatch b = new QcBatch();
        b.setBatchNo(batchNo);
        b.setProductId(rkProductId);
        b.setInspectType("IQC");
        b.setQty(1);
        b.setStatus("PENDING");
        batchMapper.insert(b);
        return b.getId();
    }

    /** AQL 用例夹具：一个专属产品 + AQL 标准 + 指定批量的批次 */
    private Long aqlBatch(String batchNo, int qty) {
        if (aqlProductId == null) {
            QcProduct p = new QcProduct();
            p.setProductCode("IT-AQL");
            p.setProductName("AQL 抽样试用件");
            p.setUnit("件");
            productMapper.insert(p);
            aqlProductId = p.getId();

            QcStandard std = new QcStandard();
            std.setStandardCode("S-IT-AQL");
            std.setProductId(aqlProductId);
            std.setVersion(1);
            std.setStatus("ACTIVE");
            std.setSamplingType("SAMPLE");
            std.setSamplingPlan("AQL");
            std.setAql(new BigDecimal("2.500"));
            std.setInspectionLevel("II");
            std.setSampleSize(1);
            std.setAllowedNgCount(0);
            standardMapper.insert(std);

            QcStandardItem si = new QcStandardItem();
            si.setStandardId(std.getId());
            si.setItemId(1L);
            si.setSeq(10);
            si.setIsCritical(true);
            si.setIsRequired(true);
            si.setValueMode("SINGLE");
            si.setPointCount(1);
            si.setMinValue(new BigDecimal("11.95"));
            si.setMaxValue(new BigDecimal("12.05"));
            si.setIsDestructive(false);
            standardItemMapper.insert(si);
        }

        QcBatch b = new QcBatch();
        b.setBatchNo(batchNo);
        b.setProductId(aqlProductId);
        b.setInspectType("IQC");
        b.setQty(qty);
        b.setStatus("PENDING");
        batchMapper.insert(b);
        return b.getId();
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

    /**
     * 造一套必然不合格的值，用来判出 NG。
     *
     * <p>数值项一律顶到上限之外，而不是给空值——空值走的是「必填未填」那条分支，
     * 测不出限值比较本身是否生效。
     */
    private List<Map<String, Object>> fillViolating(Map<String, Object> detail) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> row : rows(detail)) {
            String type = String.valueOf(row.get("valueType"));
            int points = row.get("pointCount") == null ? 1
                    : Math.max(1, ((Number) row.get("pointCount")).intValue());

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", num(row.get("id")));

            switch (type) {
                case "NUMBER" -> {
                    BigDecimal max = dec(row.get("maxValue"));
                    BigDecimal min = dec(row.get("minValue"));
                    BigDecimal bad = max != null ? max.add(BigDecimal.TEN)
                            : (min != null ? min.subtract(BigDecimal.TEN) : BigDecimal.valueOf(-999));
                    List<String> values = new ArrayList<>();
                    for (int i = 0; i < points; i++) {
                        values.add(bad.toPlainString());
                    }
                    line.put("values", values);
                }
                case "BOOL" -> {
                    boolean passTrue = !Boolean.FALSE.equals(row.get("boolPassValue"));
                    line.put("values", List.of(passTrue ? "false" : "true"));
                }
                default -> line.put("values", List.of());
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

    /**
     * 取 {code,msg,data} 里 data 是数组的接口。
     *
     * <p>不能复用 {@link #data}：它把 data 当对象转 Map，遇到数组会静默返回空 Map，
     * 于是「接口明明有数据」在断言里表现成「空列表」，查半天查不到服务端头上。
     */
    private List<Map<String, Object>> dataList(String path) {
        Map<String, Object> r = get(path);
        assertThat(num(r.get("code"))).as("接口返回失败: %s", r.get("msg")).isZero();
        return asList(r.get("data"));
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
