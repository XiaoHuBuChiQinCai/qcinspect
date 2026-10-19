# 工件质检系统（QC Inspect）

工厂内部工件质检系统。PC 端配置标准与看板，移动端在车间扫码录入检验数据，后端自动判定并统计合格率。

技术栈：**Java 21 + Spring Boot 3.5 + PostgreSQL 16 + MyBatis-Plus ｜ Vue 3 + Element Plus ｜ uni-app（H5 / 微信小程序）**

---

## 一、目录结构

```
C:\qcinspect
├── db\
│   ├── V1__init.sql          建表脚本 + 初始化数据（法兰盘示例 + 量具 + 不良代码 + 出货），全新安装只跑这个
│   ├── V2__piece_and_lock.sql       已建库升级：件粒度 + 提交锁定 + 操作日志
│   ├── V3__standard_bool_config.sql 已建库升级：标准项的可覆写布尔合格值
│   └── V4__gauge_disposition_trace.sql 已建库升级：量具 + 不良处置 + 不良代码 + 出货追溯
├── docs\
│   └── API.md                接口清单
├── qc-server\                后端
│   ├── pom.xml
│   └── src\main\java\com\qc\inspect\
│       ├── judge\            ★ 判定引擎（纯 Java，无框架依赖，已实跑验证）
│       ├── entity\           14 个实体
│       ├── mapper\           MyBatis-Plus Mapper
│       ├── service\          建单快照 / 提交判定 / 标准配置 / 量具校验 / 处置流转 / 追溯
│       ├── controller\       REST 接口
│       └── common\           统一返回、异常处理
├── qc-pc\                    PC 端（Vue 3 + Vite + Element Plus）
└── qc-mobile\                移动端（uni-app，HBuilderX 直接打开）
```

---

## 二、快速启动

### 1. 建库

本地装好 PostgreSQL 16 后：

```bash
createdb qcinspect
psql -d qcinspect -f db/V1__init.sql
```

连接信息在 `qc-server/src/main/resources/application.yml`，默认 `postgres/postgres`，改成自己的。

### 2. 起后端

```bash
cd qc-server
mvn spring-boot:run
```

> **用 JDK 21 跑**。项目已用 Maven 3.9.9 + JDK 21 完整编译验证通过（`mvn -DskipTests compile`，55 个源文件）。
> 用 JDK 23 及以上构建会踩一个坑：javac 从 23 起默认不再隐式发现 classpath 上的注解处理器，Lombok 会静默失效，
> 报错是「找不到符号: 方法 getId()」，看着像实体类写错了。pom 里已显式声明 `annotationProcessorPaths`，
> 所以新 JDK 也能编，但仍建议用 21 跑，与 `java.version` 保持一致。

启动后接口在 `http://localhost:8080/api`。

**首次启动会自动创建管理员账号**（`sys_user` 表为空时）：

| 项 | 默认值 | 覆盖用的环境变量 |
|---|---|---|
| 账号 | `admin` | `ADMIN_USER` |
| 密码 | `admin123` | `ADMIN_PASSWORD` |

登录后请立刻在右上角「修改密码」里改掉。生产环境务必用环境变量覆盖 `ADMIN_PASSWORD` 与 `JWT_SECRET`（后者至少 32 字节）。

### 3. 起 PC 端

```bash
cd qc-pc
npm install
npm run dev
```

访问 `http://localhost:5173`。已配好 `/api` 代理到 8080。

### 4. 起移动端

用 **HBuilderX** 打开 `qc-mobile` 目录：

- 运行到浏览器：直接点「运行 → 运行到浏览器」，起 H5
- 运行到微信开发者工具：点「运行 → 运行到小程序模拟器」，需要在 `manifest.json` 里填自己的小程序 AppID
- 真机调试时把 `common/api.js` 里的 `BASE` 改成电脑内网 IP，小程序开发者工具勾选「不校验合法域名」

---

## 三、已经验证过的部分

判定引擎（`qc-server/src/main/java/com/qc/inspect/judge`）是**纯 Java 实现，不依赖任何框架**，可以直接编译运行：

```bash
cd C:/qcinspect
javac -encoding UTF-8 -d .build qc-server/src/main/java/com/qc/inspect/judge/*.java qc-server/src/test/java/com/qc/inspect/judge/ItemJudgeDemo.java
java -cp .build com.qc.inspect.judge.ItemJudgeDemo
```

当前结果：**38 / 38 项断言全部通过**，覆盖：

- 数值 / 枚举 / 布尔 / 文本四种类型
- 单值 / 多点两种测量方式
- 边界值（等于上限算合格）、非法输入、测点数量不符
- **件级判定**：任一项 NG 即该件 NG，关键项 NG 单独计数
- **整批判定**：1 件 NG / 允收 1 件 → 合格；允收 0 件 → 不合格；2 件 NG / 允收 1 件 → 不合格
- **关键项在允收范围内**：判合格，但理由必须带关键项提示
- 空单判不合格、未录入单独给理由、允收数负数按 0、同件同项重复行
- 中文输入兼容（"有"/"无" 等价于 true/false）

其余代码的验证情况：

| 范围 | 方式 | 结果 |
|---|---|---|
| 判定引擎 | `javac` + `main` 实跑断言 | **38 / 38 通过** |
| 认证层（JWT + BCrypt） | `javac` + `main` 实跑断言（真实 jar 依赖） | **19 / 19 通过** |
| Spring 全量（60 个源文件） | `mvn -DskipTests compile`（Maven 3.9.9 + JDK 21） | **BUILD SUCCESS** |
| PC + 移动端 15 个 `.vue` | 模板标签配平 + `node --check` 校验 script | 全部通过 |
| 4 个 SQL 脚本 | 括号配平 + 中文标点检查 | 全部通过 |
| 运行时（接口 / 页面） | 未跑 | **没有连数据库实测过**，首次启动仍需自测一遍 |

认证层的 19 项断言覆盖：签发三段式 token、字段还原、改 payload 后验签失败、换密钥签发的 token 被拒、负数有效期立即过期、乱码/空串/null 不抛异常只返回 null、短密钥启动即报错；BCrypt 同口令通过、错口令拒绝、两次哈希不同（有随机盐）、哈希不含明文。

> 最后一条「运行时未实测」是这套项目当前最大的不确定性：数据库、登录、上传、打印这条链路我在本机没有 PostgreSQL，跑不起来。代码能编、逻辑能单测，但**没有端到端跑通过一次**。

---

## 四、四个关键设计决策

### 1. 标准版本化，永不原地改

`qc_standard` 用 `version` + `status`（DRAFT / ACTIVE / OBSOLETE）。改标准 = 发布新版本，旧版本置作废。

数据库层用**部分唯一索引**保证「同一产品同一时刻只有一个 ACTIVE」：

```sql
CREATE UNIQUE INDEX uk_standard_active ON qc_standard (product_id)
  WHERE status = 'ACTIVE' AND del_flag = 0;
```

### 2. 建单时快照

`qc_inspection_line` 把 `item_name`、`min_value`、`max_value`、`options_json`、`is_critical` 全部复制一份。

**后果**：标准怎么改版，历史检验单的判定依据都不会变。这是质量体系的基本要求 —— 三个月前的检验单，不能因为今天改了公差就变成不合格。

### 3. 检验项按 `value_type` 分四类

| 类型 | 判定逻辑 | 录入控件 |
|---|---|---|
| NUMBER | 每点都要在 `[min, max]` 内，支持单边 | 数字键盘 |
| ENUM | 选项的 `pass` 标记 | 下拉 / 选择器 |
| BOOL | 录入值是否等于 `bool_pass_value` | 有 / 无 |
| TEXT | 不判定 | 多行文本 |

**`bool_pass_value` 最容易配错**，语义是「录入该值时判为合格」：

- 「是否有毛刺」→ 无毛刺才合格 → `false`
- 「是否有材质证明」→ 有才合格 → `true`

配置界面务必显示成「合格值：有 / 无」，否则维护人员一定会配反。

### 4. 两层判定：先判件，再判批

**第一层 —— 件**：同一 `piece_no` 的所有行一起判，任一行 NG → 该件 NG。

**第二层 —— 批**：不合格件数 `failedQty` 与允收数 `acceptQty`（Ac）比较，`failedQty <= acceptQty` 判合格。

`qc_standard.allowed_ng_count` 的语义是**允许的不合格件数**，不是允许的不合格项数。抽 20 件、Ac=2，意思是最多容忍 2 件不合格。

两个必须知道的细节：

- **没有检验数据判不合格**。空单不能判合格，这是质量记录的底线。
- **关键项 NG 在允收范围内时，结论仍是合格，但理由里必须点出关键项**。系统会输出「未超过允收数 3 件。关键项问题：第 2 件 关键项不合格：硬度…」，让看单的人不至于漏掉。

---

### 5. 检验单有「件」这一层

`qc_inspection_line` 带 `piece_no`。抽 5 件、每件 8 项 → 40 行，每行只代表「第 i 件的第 j 项」。

抽检件数在建单时展开，行数 = 件数 × 项数。上限 200 件/单，超了让人拆批。

- **不这么做的后果**：一个批次只判一次，出现 NG 时你只能说“这批外径有 NG”，说不出“第 37 件的外径超差”，也没法按件算合格率。
- **数据库兜底**：唯一索引 `(inspection_id, piece_no, item_id)`，一件一项只能一行。
- **不良记录**也带 `piece_no`，追溯到具体哪一件。

合格率现在有单级和件级两个口径，`stat/daily` 两个都返回，前端展示件级。

### 6. 提交即锁定

状态机：`DRAFT → SUBMITTED → CLOSED`，`SUBMITTED` 可 `REOPEN` 回 `DRAFT`，`DRAFT`/`SUBMITTED` 可 `VOID`。

**已提交的检验单不允许原地改**，任何改动必须走 `reopen` 并填写理由，全部记进 `qc_inspection_log`。

这条不是为了防人，是为了让数据有证据价值。一条能被静默修改的检验记录，在 ISO 审核里是零分——审核员只要问一句“这个值什么时候改的、谁改的”，答不上来整份记录就作废了。

配套的还有草稿暂存（`POST /{id}/save`）：只写实测值，不判定、不改状态、不写日志。40 个输入框没人能一口气填完，没有暂存就会丢数据。

### 7. 量具不过期，数据才算数

`qc_gauge` 记编号、校准日、校准周期，到期日 = 校准日 + 周期（程序算，不手填）。

录入页数值项会带一个量具下拉，**已过期的量具直接置灰不可选**；即使绕过前端硬发请求，提交时 `GaugeService.assertUsable` 也会拦下并报「已于 yyyy-mm-dd 到期，超期量具测出的数据不可采信」。

这么做的理由很实际：卡尺校准过期 3 个月，它测出的 200 个数据在 IATF 16949 / ISO 9001 体系上全部无效，事后无法补救。**这是整个系统里投入产出最高的一块**，成本三张表，但客户一看就知道做的人懂行。

### 8. NG 不是终点，处置才是

`qc_defect` 从「一个标记」变成了有状态流转的闭环：

```
PENDING ──返工/退货──> PROCESSING ──> DONE（返工必须挂复检单）
PENDING ──报废────────> DONE
PENDING ──让步接收────> WAIVED（理由 + 授权人必填，DB 有 CHECK 约束兜底）
```

两条硬规则：

- **返工完成必须挂一张复检单**，否则无法证明返工后的产品是合格的
- **让步接收必须有理由和授权人**，这是 ISO 体系里唯一需要「有权签字的人点头」的处置方式，漏了整个评审会被挑出来

### 9. 追溯要能双向走

正向：批次 → 检验单 → 出货记录 → 客户（出问题要召回，得知道货发给谁了）
反向：出货单号 / 客诉批号 → 批次 → 检验单 → 检验员 + 当时用的量具 + 当时的实测值

**能查到「用了哪把量具」这件事，是这套系统和 Excel 的分水岭。** 所以 `qc_inspection_line` 除了 `gauge_id` 还存了一份 `gauge_code` 快照，量具台账日后改名也不影响历史记录。

### 10. 操作人只认 token，不认前端传参

JWT 里带 `id / username / realName / role`，落库的 `created_by`、`operator`、`inspector_name` 全部由 `UserContext` 从登录态取。

之前 `operator` 是 query 参数，任何人把 URL 里的名字一改，操作日志就变成别人的——**质量记录的可追责性直接归零**。现在接口仍兼容传参，但只要带了 token，一律以 token 为准。

口令用 BCrypt，`sys_user.password` 加了 `@JsonIgnore`，任何接口都不会把它带出去。账号不存在与密码错误返回同一句话，避免被用来枚举账号。

### 11. 图片默认落本地盘，MinIO 是可选项

`qc.storage.type` 两个取值：

| 取值 | 存哪 | 浏览器怎么取 |
|---|---|---|
| `local`（默认） | `${STORAGE_DIR:./data/files}`，按 `yyyy/MM/dd` 分目录 | `/api/images/{id}/raw` 代理 |
| `minio` | MinIO bucket，启动时自动建桶 | 7 天预签名地址 |

默认给本地，是因为单厂内网部署多养一套 MinIO 是纯运维成本。但对象键从一开始就按 `yyyy/MM/dd/uuid.ext` 生成，切到 MinIO 不用改数据。

`/raw` 接口放行（不校验登录）是因为 `<img src>` 带不了请求头；内部系统可接受，**如果要做公网部署，这里是第一个要收紧的口子**。

图片上传只接收 jpg/jpeg/png/webp/bmp，单张 10MB 上限，Content-Type 按扩展名反推而不是信浏览器。

### 12. 报告是「数据冻结后的只读视图」

`GET /api/reports/inspection/{id}` 只读，不写库。它取的全是检验单里的快照字段，所以：

- 标准后来改版，三个月前的报告不会变样
- 草稿状态也能出报告，但会带 `conclusion.final = false`，前端顶部显示「未提交，判定未生效」——防止有人拿草稿当合格证
- 打印走浏览器原生 `window.print()` + `@page A4`，不引 PDF 库。要归档就「另存为 PDF」

---

## 五、数据库表清单

| 表 | 说明 |
|---|---|
| `qc_product` | 产品（被检工件） |
| `qc_item` | 检验项主数据，跨产品复用 |
| `qc_standard` | 检验标准（产品 + 版本） |
| `qc_standard_item` | 标准包含的检验项（一个标准 N 项） |
| `qc_batch` | 批次 |
| `qc_inspection` | 检验单（含件数统计 passed_qty / failed_qty / accept_qty） |
| `qc_inspection_line` | 检验明细，件 × 项，含标准快照 |
| `qc_defect` | 不良记录，带件号 |
| `qc_inspection_log` | 操作日志，提交/解锁/归档/作废全部留痕 |
| `qc_gauge` | 量具台账，含校准周期与到期日 |
| `qc_defect_code` | 不良代码字典，帕累托的统计维度 |
| `qc_shipment` | 出货记录，追溯的出向 |
| `qc_image` | 检验照片，本地盘或 MinIO |
| `sys_user` | 用户（BCrypt 口令，ADMIN / INSPECTOR） |

---

## 六、下一步

已闭环：件粒度、两层判定、提交锁定、草稿暂存、件级合格率、标准配置编辑页、量具校准、NG 处置、不良代码帕累托、双向往溯、认证与用户管理、图片上传、检验报告打印。

按优先级还剩：

1. **移动端补齐新功能**：量具选择、不良处置、报告查看目前只在 PC 端有；移动端已有登录和拍照上传的底层方法（`uploadImage`），页面还没接
2. **合格率口径**：目前同时给单级和件级两个数，接真实客户后要确认他们要哪个
3. **用户与角色细化**：现在只有 ADMIN / INSPECTOR 两级，真实场景通常还要「审核员」（能 reopen 但改不了值）和「只读」
4. **不良代码字典维护页**：接口有，PC 端还没做编辑界面
5. **数据导出**：合格率、帕累托目前只能看不能导 Excel

暂不做：AQL 比例抽样、SPC 控制图、CPK 数值（样本量不足时算出来是错的，宁可不给）。

---

## 七、注意

- **升级**：全新安装只跑 `db/V1__init.sql`。已建过库的按顺序跑 V2 → V3 → V4，**V1 与 V2/V3/V4 不要都跑**（内容重叠，会重复建索引报错）
- **认证**：除 `/api/auth/login`、`/api/images/{id}/raw`、`/actuator/health` 外，所有接口都要 `Authorization: Bearer <token>`，否则返回 401
- **JWT 密钥**：`qc.jwt.secret` 至少 32 字节，默认值是开发用的，上线必须用 `JWT_SECRET` 覆盖（启动时会校验长度，不够直接报错）
- **件数上限**：单张检验单最多 200 件，超了让人拆批。件数 × 项数 就是明细行数，1000 件全检会把表写爆
- 所有业务表带 `del_flag` 逻辑删除，MyBatis-Plus 全局配置已接管，删除走 `deleteById` 不会真删
- 时间统一 `timestamptz`，实体用 `LocalDateTime`
- JDBC URL 带了 `stringtype=unspecified`，这是为了让 `String` 能直接写进 `jsonb` 列，别删
