# 工件质检系统（QC Inspect）

工厂内部工件质检系统。PC 端配置标准与看板，移动端在车间扫码录入检验数据，后端自动判定并统计合格率。

技术栈：**Java 21 + Spring Boot 3.5 + PostgreSQL 16 + MyBatis-Plus ｜ Vue 3 + Element Plus ｜ uni-app（H5 / 微信小程序）**

---

## 一、目录结构

```
C:\qcinspect
├── docker-compose.yml        本地开发：PostgreSQL 16 + MinIO + 后端（不含前端页面）
├── docker-compose.prod.yml   生产部署：PG + 后端 + Nginx 托管 PC 页面，适配 2C2G
├── .env.example              生产环境变量模板（复制成 .env 后改密码）
├── scripts\
│   └── smoke.py              端到端冒烟测试（登录→建单→提交→审核→导出）
├── db\
│   ├── V1__init.sql          建表脚本 + 初始化数据（法兰盘示例 + 量具 + 不良代码 + 出货）
│   ├── V2__piece_and_lock.sql       增量：件粒度 + 提交锁定 + 操作日志
│   ├── V3__standard_bool_config.sql 增量：标准项的可覆写布尔合格值
│   ├── V4__gauge_disposition_trace.sql 增量：量具 + 不良处置 + 不良代码 + 出货追溯
│   ├── V5__review_and_role.sql      增量：审核闭环 + 审核员角色 + 明细量具提示
│   ├── V6__notice.sql               增量：站内通知（审核退回 / 让步放行）
│   ├── V7__sampling_plan.sql        增量：AQL 抽样方案（GB/T 2828.1 一般检验水平 II / AQL 2.5）
│   ├── V8__recheck.sql              增量：返工复检闭环（复检单来源字段 + 部分唯一索引）
│   ├── V9__shipment_void.sql        增量：出货单作废（红冲）状态与理由
│   ├── V10__gauge_calibration.sql   增量：量具校准履历（证书/机构/结论）+ 停用状态
│   ├── V11__complaint.sql           增量：客户投诉登记与闭环（追溯的回向）
│   ├── V12__supplier.sql            增量：供应商主数据（批次上的自由文本收口）
│   └── V13__backup_log.sql          增量：备份执行记录（页面上的「上次成功是几小时前」读它）
├── docs\
│   ├── API.md                接口清单
│   └── DEPLOY.md             部署指南：镜像选型、2G 内存账、swap 与 OOM、小程序 HTTPS
├── qc-server\                后端
│   ├── pom.xml
│   ├── Dockerfile
│   ├── src\main\java\com\qc\inspect\
│   │   ├── judge\            ★ 判定引擎（纯 Java，无框架依赖，有完整单测）
│   │   ├── entity\           14 个实体
│   │   ├── mapper\           MyBatis-Plus Mapper
│   │   ├── service\          建单快照 / 提交判定 / 审核流转 / 标准配置 / 量具校验 / 处置流转 / 追溯 / 待办聚合 / 站内通知 / CSV 导出
│   │   ├── controller\       REST 接口
│   │   └── common\           统一返回、异常处理
│   └── src\test\java\com\qc\inspect\
│       ├── judge\            ItemJudgeTest / InspectionJudgeTest
│       └── auth\             JwtUtilTest
├── qc-pc\                    PC 端（Vue 3 + Vite + Element Plus），含 Dockerfile 与 nginx.conf
│   ├── src\utils\            纯函数：粘贴解析、批量录入矩阵（不碰 DOM，`npm test` 覆盖）
│   └── test\                 node --test 用例
└── qc-mobile\                移动端（uni-app），HBuilderX 或 `npm run build:h5` 均可构建
```

> V2~V13 里的 DDL 全部写成 `IF NOT EXISTS` / `DROP ... IF EXISTS`，可以重复执行。
> 所以新库和老库的升级路径是同一条：按文件名顺序跑一遍就行，不需要区分「这是新库还是旧库」。

---

## 二、快速启动

### 方式 A：Docker 一键启动（推荐）

装了 Docker Desktop 的话，不需要本机装 PostgreSQL，也不用管建库脚本跑没跑：

```bash
docker compose up -d --build
```

起来之后：

| 入口 | 地址 | 账号 |
|---|---|---|
| 后端接口 | http://localhost:8080/api | `admin` / `admin123` |
| MinIO 控制台 | http://localhost:9001 | `minioadmin` / `minioadmin` |
| PostgreSQL | localhost:5432 / 库 `qcinspect` | `qc` / `qcpass` |

数据库初始化是自动的：`db/` 下 V1~V13 按顺序挂进 PostgreSQL 的 `docker-entrypoint-initdb.d`，首次启动时执行一遍，演示数据（法兰盘 + 标准 + 量具 + 不良代码 + 出货）一并建好。

跑一遍端到端冒烟测试，验证整条链路：

```bash
python3 scripts/smoke.py
```

它会把「登录 → 找批次 → 建单 → 按限值自动填实测值 → 提交判定 → 审核归档 → 查操作日志 → 导出台账」全走一遍，任何一步不符合预期就退出码 1 并指出卡在哪。

常用命令：

```bash
docker compose logs -f app     # 跟后端日志
docker compose down            # 停止，保留数据
docker compose down -v         # 停止并清空数据（数据库 + 照片）
```

生产部署前必须改掉这三个：`DB_PASSWORD`、`ADMIN_PASSWORD`、`JWT_SECRET`（写进 `.env` 或环境变量，`docker compose` 会自动读同名 shell 变量）。

> **本编排只起后端和数据库，8080 上没有 PC 页面**（后端不托管前端静态文件）。
> 本地开发时 PC 端另行 `npm run dev`（见方式 B）。
> 要一次性起「PG + 后端 + PC 页面」的完整环境，用 `docker-compose.prod.yml`，
> 部署到云服务器的完整步骤（含镜像选型、2G 内存怎么分配、构建 OOM 怎么躲）见 **[docs/DEPLOY.md](docs/DEPLOY.md)**。

> 用 JDK 21 跑**是硬要求**，不是建议。项目已用 Maven 3.9.9 + JDK 21 完整编译验证通过。
> 用 JDK 23 及以上构建会踩一个坑：javac 从 23 起默认不再隐式发现 classpath 上的注解处理器，Lombok 会静默失效，
> 报错是「找不到符号: 方法 getId()」，看着像实体类写错了。pom 里已显式声明 `annotationProcessorPaths`，
> 所以新 JDK 也能编，但仍建议用 21 跑，与 `java.version` 保持一致。

### 方式 B：手工启动

#### 1. 建库

本地装好 PostgreSQL 16 后：

```bash
createdb qcinspect
psql -d qcinspect -f db/V1__init.sql
psql -d qcinspect -f db/V2__piece_and_lock.sql
psql -d qcinspect -f db/V3__standard_bool_config.sql
psql -d qcinspect -f db/V4__gauge_disposition_trace.sql
psql -d qcinspect -f db/V5__review_and_role.sql
```

连接信息在 `qc-server/src/main/resources/application.yml`，默认 `postgres/postgres`，改成自己的。

#### 2. 起后端

```bash
cd qc-server
mvn spring-boot:run
```

启动后接口在 `http://localhost:8080/api`。

**首次启动会自动创建管理员账号**（`sys_user` 表为空时）：

| 项 | 默认值 | 覆盖用的环境变量 |
|---|---|---|
| 账号 | `admin` | `ADMIN_USER` |
| 密码 | `admin123` | `ADMIN_PASSWORD` |

登录后请立刻在右上角「修改密码」里改掉。生产环境务必用环境变量覆盖 `ADMIN_PASSWORD` 与 `JWT_SECRET`（后者至少 32 字节）。

##### 2.1 三种角色与演示账号

系统按「谁录入、谁放行」把权限拆成三级：

| 角色 | 能做什么 | 不能做什么 |
|---|---|---|
| `ADMIN` 管理员 | 全部功能；用户管理、标准/量具/不良代码等主数据维护 | —— |
| `INSPECTOR` 检验员 | 建单、录入、提交、处置不良 | 审核放行、作废单据、改主数据 |
| `REVIEWER` 审核员 | 审核通过归档、退回、作废、查看全部数据 | 改主数据、改已提交的实测值 |

**没有任何 SQL 内置演示账号**——BCrypt 哈希写死在脚本里既不可解释也像后门。用 `admin` 登录后到「用户管理」里建：

- `inspector` / `INSPECTOR` → 移动端扫码录入
- `reviewer` / `REVIEWER` → PC 端审核放行

一个人建议只配一个角色。审核员同时是检验员时，系统仍会拦住「审核自己录入的单据」。

#### 3. 起 PC 端

```bash
cd qc-pc
npm install
npm run dev
```

访问 `http://localhost:5173`。已配好 `/api` 代理到 8080。

#### 4. 起移动端

**方式一：命令行**（装了 Node 就能用，不依赖 HBuilderX）

```bash
cd qc-mobile
npm install
npm run dev:h5      # http://localhost:5174，/api 已代理到 8080
```

**方式二：HBuilderX** 打开 `qc-mobile` 目录：

- 运行到浏览器：直接点「运行 → 运行到浏览器」，起 H5
- 运行到微信开发者工具：点「运行 → 运行到小程序模拟器」，需要在 `manifest.json` 里填自己的小程序 AppID
- 真机调试时把 `common/api.js` 里 `#ifndef H5` 分支的 `BASE` 改成电脑内网 IP，开发者工具勾选「不校验合法域名」

> 两种方式构建的是同一个项目：源码保持在根目录（HBuilderX 版结构），
> CLI 通过 `UNI_INPUT_DIR=.` 指回来。详见 `docs/DEPLOY.md` 第五节。

---

## 三、已经验证过的部分

### 1. 单元测试 + 集成测试：197 个，`mvn test` 一条命令跑完

```bash
cd qc-server
mvn test
```

```
Tests run: 197, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 数量 | 跑的是什么 | 耗时 |
|---|---|---|---|
| `ItemJudgeTest` | 45 | 四种类型的判定、边界值、测点数量、布尔项合格值方向，以及「数据不完整」与「指标不合格」的分流 | < 0.1 s |
| `InspectionJudgeTest` | 23 | 两层判定、允收数语义、关键项提示、行数与件数不混用、允收数洗不掉漏填 | < 0.1 s |
| `JwtUtilTest` | 9 | 验签、篡改、换密钥、过期、弱密钥、BCrypt | < 1 s |
| `SamplingPlanServiceTest` | 5 | AQL 抽样表与国标数值逐行比对、批量区间首尾端点、超范围兜底 | < 0.1 s |
| `MonthlyReportTest` | 8 | 月报比率：分母 0 返回 null 而不是 0%、PPM 换算、环比用百分点、分组汇总 | < 0.1 s |
| `PendingBatchInsightTest` | 9 | 漏检提醒：当天建批不报、阈值下限保护、出货未检优先于阈值、严重度排序 | < 0.1 s |
| `InspectorInsightTest` | 13 | 检验员分析：零不良的样本门槛、只报快不报慢、组间偏离双向、合计按总量重算 | < 0.1 s |
| `BackupHealthTest` | 13 | 备份健康：空表不显示成「0 小时前刚成功」、36/72 小时双阈值边界、失败优先于新鲜的成功、负小时夹紧 | < 0.1 s |
| `StandardDiffTest` | 19 | 标准改版方向：上限调高/下限调低才是放宽、关键项取反是放宽、新增项反而是收紧、BigDecimal 小数位不同不算变更、布尔反转单列 CHANGED | < 0.1 s |
| `StaleRiskTest` | 11 | 改版遗留的风险档：收紧后仍放行才是合规风险、放宽后判 NG 只算可能误判、判不出方向绝不猜成高危、排序权重单调（工作台前 8 行不能把该看的挤出去） | < 0.1 s |
| `BackupHealthServiceTest` | 4 | 备份体积的写法：不足 1 KB 报字节、十几 KB 报 KB、1 MB 边界不许出现「1024 KB」 | < 0.1 s |
| `MonthlyNarrativeTest` | 8 | 月报措辞：空月不说 0%、上月无数据不编环比、持平不说「上升 0」、客诉与量具风险点名 | < 0.1 s |
| `InspectionFlowIT` | 30 | **真实 PostgreSQL + 真实 HTTP + 真实 JWT**：建单 → 填报 → 提交 → 锁定 → 审核归档 → 退回 → 导出 → 待办 → 退回通知与越权读拦截 → AQL 抽样取值 → 出货超发拦截与例外放行 → 筛选/导出/统计三处口径一致 → 返工复检闭环 → 出货红冲与额度回退 → 主数据编号唯一与枚举/布尔校验 → 工作台待办口径与批次号回填 → 量具校准履历（同日不重登/补录不动有效期/不合格停用） → 操作日志全局检索与权限 → 客诉登记校验/未关闭拦出货/关闭三项必填/追溯可见 → 过程能力 Cpk 手算核对/草稿不计入/超规值不剔除 → 月报按月聚合/分组汇总回总量/空月不假警报/非法月份中文提示 → 供应商主数据（存量文本自动建档/重名与越权拦截/批次挂档取名称快照/停用不可选/统计按档归并） → 检验员分析（合计=各行之和/草稿不计/单量不够不告警/检验员无权查看） → 待检批次（当天不报/超期点名/出货未检升最高级/作废后批次退回待检） → 备份健康（空表不假装正常/成功按小时算/失败优先/检验员无权查看） → 批次进度（草稿算抽检不算已判/复检算第二单/作废后退回） → 标准改版（publish 出草稿才改得动/上限调高判为放宽） → 帕累托可选参数全空不 500 → 标准上下文（改版后老单说得出放宽、快照不动） → 旧版标准单据汇总（作废版还挂几张单/按版本筛明细） | ~ 54 s |

集成测试用 `io.zonky.test` 的嵌入式 PostgreSQL 起一个真库，按 V1 → V13 顺序执行仓库里的建表脚本，再用 `TestRestTemplate` 走完整的 HTTP + JWT 链路。**不需要 Docker**，`mvn test` 直接跑；`*IT` 已在 surefire 里显式纳入，CI 不会漏。

为什么不用 H2 图省事：本项目用了 `jsonb`、`timestamptz`、带 `WHERE` 的部分唯一索引和窗口函数，H2 的 PostgreSQL 兼容模式跑不过去；就算跑过了也不能证明生产能跑。宁可多下一个几十兆的二进制包。

### 2. 前端纯函数测试：36 个，`npm test` 跑

```bash
cd qc-pc
npm test
```

```
# tests 36
# pass 36
# fail 0
```

只测不碰 DOM 的那部分逻辑：把 Excel 粘进来的文本切成矩阵（`utils/pasteGrid.js`）、
把「件 × 检验项」矩阵写回单据明细（`utils/batchMatrix.js`）。

这类代码错一格**不会报错**，只会把 3 号件的读数写到 4 号件上，判出来的结果凑巧还能看——
靠肉眼和构建都发现不了，只能靠测试钉住。用例覆盖：`\r\n` 与末尾空行、整块与单列粘贴、
多测点项拆列、回写时件号与测点不错位、超规与「填了非数字」的区分、矩阵行数多于件数时忽略。

### 3. 集成测试抓出的三个真缺陷

三个都是「编译通过、单测通过、一调接口就 500」的类型，也正好说明端到端验证省不得：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 建库直接失败：`语法错误 在 'true = 录「有」算合格…' 或附近` | V3 里有一条 `COMMENT` 用「相邻字符串字面量」拆成两行。psql 支持换行拼接，但 Spring 的 `spring.sql.init` 会把换行压成空格再发，PostgreSQL 于是报语法错 | 合并成单个字面量 |
| 2 | 所有查询 500：`Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime` | 时间列全是 `timestamptz`，实体是 `LocalDateTime`；MyBatis 内置 handler 走 `getObject(col, LocalDateTime.class)`，PG 驱动只认不带时区的 `timestamp` | 自定义 `TimestamptzTypeHandler`：读 `OffsetDateTime` 再按系统时区还原 |
| 3 | 建单 500：`无法确定参数 $1 的数据类型` | 单号统计 `CONCAT('QC', #{day}, '%')` 的占位符没有类型，PostgreSQL 无法推断 | `CAST(#{day} AS text)` |

第 2、3 条如果没跑真库，上线第一天就会撞上：登录之后所有列表空白，或者一张单都开不出来。

### 4. 其他验证情况

| 范围 | 方式 | 结果 |
|---|---|---|
| 后端全量 | `mvn -DskipTests compile`（Maven 3.9.9 + JDK 21） | **BUILD SUCCESS** |
| 单元 + 集成测试 | `mvn test`（含嵌入式 PostgreSQL） | **197 / 197 通过** |
| 13 个 SQL 脚本 | 括号配平 + 幂等性核对 + **在真库上实际执行过**（集成测试） | 全部通过 |
| PC 端生产构建 | `npm install` + `npm run build`（Node 22.22.2） | **成功**，dist 1.6 MB，主包 gzip 371 KB |
| PC + 移动端 28 个 `.vue` | 模板标签配平 + `node --check` 校验 script；PC 端另由 `vite build` 全量编译 | **28 / 28 通过**（`qc-mobile/App.vue` 无 template，只校验 script） |
| 前端纯函数 | `npm test`（node:test，无额外依赖） | **36 / 36 通过** |
| Docker 编排 | `docker-compose.yml` 结构、脚本挂载顺序 | 只做了结构与 YAML 校验，**本机无 Docker，没实跑过** |

> 现在唯一还悬着的是 Docker 那条路：`docker compose up` 里的 `docker-entrypoint-initdb.d` 用 psql 执行脚本，与集成测试用的 Spring 脚本执行器不是同一条路径（这正是缺陷 1 的成因），所以两者都值得各跑一次。

---

## 四、十三个关键设计决策

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

### 6. 提交即锁定，审核才放行

状态机：`DRAFT → SUBMITTED →（审核通过）CLOSED`，`SUBMITTED` 可 `REOPEN` 回 `DRAFT`，也可被审核退回回 `DRAFT`，`DRAFT`/`SUBMITTED` 可 `VOID`。

**已提交的检验单不允许原地改**，任何改动必须走 `reopen` 并填写理由，全部记进 `qc_inspection_log`。

这条不是为了防人，是为了让数据有证据价值。一条能被静默修改的检验记录，在 ISO 审核里是零分——审核员只要问一句“这个值什么时候改的、谁改的”，答不上来整份记录就作废了。

配套的还有草稿暂存（`POST /{id}/save`）：只写实测值，不判定、不改状态、不写日志。40 个输入框没人能一口气填完，没有暂存就会丢数据。

**为什么提交和放行要分成两个人**：检验员自己录完自己放行，叫「自检自判」，是体系漏洞。所以 `SUBMITTED` 不能直接 `CLOSED`，必须经 `REVIEWER` 审核。三条硬规则：

- 审核不通过必须写意见——否则检验员拿着空白的退回单，不知道要改什么
- 不允许审核本人录入的单据（管理员例外，但日志里会标注「管理员代审本人录入单据」）
- 审核痕迹落三个字段：`reviewed_by` / `reviewed_at` / `review_comment`，加上日志里的 `REVIEW` / `REJECT` 两条动作，能完整回答「当时凭什么放行」

批次状态也跟着走：提交后批次仍是「检验中」，审核通过才变「已完成」。否则看板上会把没人审过的数据提前算进合格率。

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

### 13. 导出 CSV 而不是 Excel

`GET /api/export/inspections | /defects | /inspections/{id}/lines` 三张台账，返回 `text/csv`。

不引 POI 的理由：这三张表交付出去就是被丢进 Excel 二次透视的，样式反而是干扰；省掉几十兆依赖，也省掉「导出模板改版」这类维护。真要带版面的表单走报告打印。

两个容易被忽略的细节：

- **UTF-8 BOM**：不加这三个字节，Excel 双击打开中文全是乱码，用户第一反应是「系统有问题」
- **公式注入**：以 `=` `+` `@` 开头、或 `-` 后跟非数字的单元格，Excel 会当公式执行。导出的是别人填的备注，所以统一加前缀 `'` 转成纯文本；`-0.02` 这类正常负值不受影响

---

## 五、数据库表清单

| 表 | 说明 |
|---|---|
| `qc_product` | 产品（被检工件） |
| `qc_item` | 检验项主数据，跨产品复用 |
| `qc_standard` | 检验标准（产品 + 版本） |
| `qc_standard_item` | 标准包含的检验项（一个标准 N 项） |
| `qc_batch` | 批次 |
| `qc_inspection` | 检验单（含件数统计 passed_qty / failed_qty / accept_qty、审核痕迹 reviewed_by / reviewed_at） |
| `qc_inspection_line` | 检验明细，件 × 项，含标准快照与建议量具 |
| `qc_defect` | 不良记录，带件号 |
| `qc_inspection_log` | 操作日志，提交/解锁/审核/退回/归档/作废全部留痕 |
| `qc_gauge` | 量具台账，含校准周期与到期日 |
| `qc_defect_code` | 不良代码字典，帕累托的统计维度 |
| `qc_shipment` | 出货记录，追溯的出向 |
| `qc_image` | 检验照片，本地盘或 MinIO |
| `qc_notice` | 站内通知，审核退回与让步放行主动落到接收人待办 |
| `qc_sampling_plan` | AQL 抽样方案表，批量 → 字码 → 样本量 n / 允收数 Ac |
| `sys_user` | 用户（BCrypt 口令，ADMIN / INSPECTOR / REVIEWER） |

---

## 六、下一步

已闭环：件粒度、两层判定、提交锁定、草稿暂存、件级合格率、标准配置编辑页、量具校准、NG 处置、不良代码帕累托与字典维护、双向追溯、认证与三角色权限、审核放行闭环、图片上传、检验报告打印、CSV 导出（检验单/不良/明细/帕累托/日合格率）、移动端量具选择·现场拍照·报告查看·续填草稿、待办聚合与角标、站内通知与审核退回主动推送、移动端让步接收、**AQL 抽样（GB/T 2828.1，样本量与允收数按批量查表）**、**出货登记与超发拦截**、**检验单多条件筛选（单号/批次号/产品/检验员/结论/日期区间，列表与导出同口径）**、**检验单操作轨迹时间线**、**供应商来料合格率排名**、**返工复检闭环（未返工不许开复检、复检不合格不许闭环）**、**出货单红冲（理由必填、额度回退、保留痕迹）**、**主数据录入闭环（产品·检验项维护页 + 新建批次 + 保存前置校验）**、**PC 工作台（待审核/待处置/超期量具/我的草稿一处集中，登录即首页）**、**量具校准履历（证书号/校准机构/结论，同日不重登、补录不动有效期、不合格自动停用）**、**操作日志全局检索（按动作/操作人/单号批次/日期筛，可导出，仅管理员）**、**客户投诉闭环（登记必填批次/客户/描述；未关闭客诉拦住该批次后续出货；关闭需根因+措施+责任方；追溯页可见；可导出）**、**过程能力分析（SPC：Cp/Cpk 用组内 σ̂、Pp/Ppk 用整体 s，超规值不剔除，配直方图与 I-MR 控制图）**、**批量录入（件 × 检验项矩阵，从 Excel 整块粘贴，超规当场标红）**、**录入即时校验（超规当场标红，PC 与移动端一致）**、**数据完整性拦截（漏填/脏值不受允收数 Ac 消化，整批直判不合格）**、**质量月报（按月汇总合格率/主要不良/供应商/客诉/量具/返工 + 环比百分点 + 自动结论，可打印成管理评审用的一页纸）**、**供应商主数据（V12，批次只能选档不能手打，存量自由文本自动建档并回填，统计按档归并）**、**检验员分析（按人看记录可不可信：零不良 / 录入过快 / 不良率偏离同组，只提示复核不做排名）**、**待检批次漏检提醒（建了批次没开单、已出货未检，工作台点名；作废检验单后批次退回待检）**、**备份健康可见（V13：备份脚本每次执行都落库，工作台对管理员显示「上次成功是几小时前」，36/72 小时双阈值报警——备份坏了不会自己喊，只能靠这个逼它喊）**、**批次检验进度（批次列表显示「判了几件 / 抽了几件」，草稿算抽检不算已判、复检算第二单、作废单不进进度）**、**标准改版对比（`GET /standards/{id}/diff`：逐项判出放宽还是收紧，五档结论 RELAXED/TIGHTENED/MIXED/CHANGED/NEUTRAL；顺手修掉「发布新版本直接生效导致改不了检验项」的死结）**、**检验单标准上下文（详情里带 `standardContext`：本单按 v3 判的、现行已到 v5、中间放宽了哪几项；历史单快照不动，新单用新版）**、**旧版标准单据汇总（`GET /standards/stale-usage`：作废版本下还挂着几张判定单、判成什么，点进去按 `standardId` 看明细）**、Docker 一键启动、186 个后端测试 + 36 个前端纯函数测试、端到端冒烟脚本。

按优先级还剩：

1. **Docker 那条路实跑一次**：集成测试已经用真 PostgreSQL 跑通全链路，但 `docker compose up` 走的是 psql 执行脚本，是另一条路径。找台有 Docker 的机器跑一遍 `docker compose up -d --build && python3 scripts/smoke.py`
2. **合格率口径**：页面和导出都同时给单级与件级，接真实客户后要确认他们要哪个（大概率要件级，但要客户自己点头）
3. **通知的出站通道**：站内通知已经闭环（PC 顶栏铃铛 + 移动端未读横幅，60 秒轮询），
   但人不在系统里时仍然收不到。要真正做到「推到人」，下一步接企业微信应用消息或短信，
   现在的 `qc_notice` 就是那条出站队列的源
4. **单点登录与组织架构**：现在账号是本地表，接客户环境通常要对接他们的 AD / 企业微信通讯录

暂不做：计数型以外的计量型抽样（GB/T 6378）、SPC 控制图、CPK 数值（样本量不足时算出来是错的，宁可不给）。

---

## 七、注意

- **升级**：V2~V13 全部写成幂等 DDL（`ADD COLUMN IF NOT EXISTS` / `DROP ... IF EXISTS` / `CREATE INDEX IF NOT EXISTS`），按 V1 → V13 顺序执行即可，新库老库同一条路径。**但 V1 不是幂等的**（建表用的是普通 `CREATE TABLE`），已经跑过 V1 的库不要再跑一遍
- **认证**：除 `/api/auth/login`、`/api/images/{id}/raw`、`/actuator/health` 外，所有接口都要 `Authorization: Bearer <token>`，否则返回 401
- **权限**：主数据维护（标准/量具/检验项/不良代码）与用户管理要 `ADMIN`；审核/归档/作废要 `ADMIN` 或 `REVIEWER`；越权返回 403，前端的菜单隐藏只是体验，真正的边界在 `@PreAuthorize`
- **JWT 密钥**：`qc.jwt.secret` 至少 32 字节，默认值是开发用的，上线必须用 `JWT_SECRET` 覆盖（启动时会校验长度，不够直接报错）
- **件数上限**：单张检验单最多 200 件，超了让人拆批。件数 × 项数 就是明细行数，1000 件全检会把表写爆
- 所有业务表带 `del_flag` 逻辑删除，MyBatis-Plus 全局配置已接管，删除走 `deleteById` 不会真删
- 时间统一 `timestamptz`，实体用 `LocalDateTime`
- JDBC URL 带了 `stringtype=unspecified`，这是为了让 `String` 能直接写进 `jsonb` 列，别删
