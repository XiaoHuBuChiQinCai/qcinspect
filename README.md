# 工件质检系统（QC Inspect）

工厂内部工件质检系统。PC 端配置标准与看板，移动端在车间扫码录入检验数据，后端自动判定并统计合格率。

技术栈：**Java 21 + Spring Boot 3.5 + PostgreSQL 16 + MyBatis-Plus ｜ Vue 3 + Element Plus ｜ uni-app（H5 / 微信小程序）**

---

## 一、目录结构

```
C:\qcinspect
├── db\
│   ├── V1__init.sql          建表脚本 + 初始化数据（法兰盘示例），全新安装只跑这个
│   └── V2__piece_and_lock.sql  V1 老库的原地升级脚本（补件粒度 + 操作日志）
├── docs\
│   └── API.md                接口清单
├── qc-server\                后端
│   ├── pom.xml
│   └── src\main\java\com\qc\inspect\
│       ├── judge\            ★ 判定引擎（纯 Java，无框架依赖，已验证）
│       ├── entity\           10 个实体
│       ├── mapper\           MyBatis-Plus Mapper
│       ├── service\          建单快照 / 提交判定 / 标准版本管理
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

> 本机当前没有装 Maven。用 IntelliJ IDEA 打开 `qc-server/pom.xml`，IDEA 自带 Maven，直接运行 `QcApplication` 即可。或者装一个 Maven 后跑上面的命令。

启动后接口在 `http://localhost:8080/api`。

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

其余代码（Spring 层）因为本机没有 Maven 依赖，未做编译验证，用 IDEA 导入后如有报错按提示修。

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
| `qc_image` | 检验照片（MinIO） |
| `sys_user` | 用户 |

---

## 六、下一步

已完成的模型改造：件粒度、两层判定、提交锁定、草稿暂存、件级合格率。

按优先级还剩：

1. **NG 处置闭环**：`qc_defect.disposition` 现在只是个标记，还需要处置状态流转、返工后复检回链原单、让步接收的授权人签字
2. **量具校准**：一张表记编号/校准日/有效期，检验单记录用了哪把量具，录入时选到过期量具就拦截
3. **批次追溯查询页**：正向原料批 → 客户，反向客诉批号 → 检验记录 → 检验员 → 量具
4. **认证**：`sys_user` 表已建，补 Spring Security + JWT，Controller 加鉴权（`operator` 现在是 query 参数，接上认证后应从 token 取）
5. **图片上传**：MinIO 已引依赖，补 `ImageController` + 移动端拍照上传
6. **标准配置编辑页**：目前 `StandardList.vue` 只能看版本和发布新版，还缺「勾选检验项 + 设限值」的编辑页
7. **不良代码字典 + 帕累托**：`qc_defect.defect_code` 现在可以随便填，没有主数据约束，帕累托排名会散
8. **检验报告 / 合格证打印**

暂不做：AQL 比例抽样、SPC 控制图、CPK 数值（样本量不足时算出来是错的，宁可不给）。

---

## 七、注意

- **升级**：V1 建过库的跑 `db/V2__piece_and_lock.sql`；全新安装只跑 V1，**不要再跑 V2**（两者内容重叠）
- **件数上限**：单张检验单最多 200 件，超了让人拆批。件数 × 项数 就是明细行数，1000 件全检会把表写爆
- 所有业务表带 `del_flag` 逻辑删除，MyBatis-Plus 全局配置已接管，删除走 `deleteById` 不会真删
- 时间统一 `timestamptz`，实体用 `LocalDateTime`
- JDBC URL 带了 `stringtype=unspecified`，这是为了让 `String` 能直接写进 `jsonb` 列，别删
