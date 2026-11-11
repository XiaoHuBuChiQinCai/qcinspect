# 接口清单

Base URL：`http://localhost:8080/api`
统一返回：`{ "code": 0, "msg": "ok", "data": ... }`，`code != 0` 表示失败。

> **认证**：除登录接口外，全部需要请求头 `Authorization: Bearer <token>`。
> 未带 / 过期返回 HTTP 401 + `{ "code": 401, "msg": "未登录或登录已过期" }`；权限不足返回 403。
>
> **角色**：`ADMIN` 管理员 / `INSPECTOR` 检验员 / `REVIEWER` 审核员。下表的「权限」列写的就是接口要求的角色，
> 由 `@PreAuthorize` 在后端强制，前端隐藏菜单只是体验层面的事。
>
> **操作人**：`operator` 这类参数仍然接受，但只要带了有效 token，一律以 token 里的用户为准。
> 检验员姓名、操作日志的操作人都是这么来的——质量记录的责任人不能由前端随便填。

---

## 〇、认证

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/auth/login` | 登录，返回 token | 公开 |
| GET | `/auth/me` | 当前登录人 | 登录 |
| POST | `/auth/password` | 修改自己的密码 | 登录 |
| GET | `/auth/users` | 用户列表 | ADMIN |
| POST | `/auth/users` | 新增 / 修改用户 | ADMIN |
| POST | `/auth/users/{id}/reset-password` | 重置他人密码（顺带解锁） | ADMIN |
| POST | `/auth/users/{id}/unlock` | 解锁被「连续密码错误」锁住的账号 | ADMIN |

**登录**
```json
// 请求
{ "username": "admin", "password": "admin123" }

// 响应 data
{
  "token": "eyJhbGciOiJIUzI1NiJ9....",
  "user": { "id": 1, "username": "admin", "realName": "系统管理员", "role": "ADMIN" },
  "expireMinutes": 480
}
```
> 账号不存在与密码错误返回同一句「账号或密码错误」，避免被用来枚举账号。
> 账号停用返回「账号已停用，请联系管理员」。

**登录失败锁定**

连续 5 次密码错误锁定该账号 15 分钟，到点自动解开，不需要人操作。第 5 次的响应会直接说明
「账号已锁定 15 分钟」，锁定期间的任何请求（**包括密码正确的**）都返回「请 N 分钟后再试」。

| 次数 | 提示 |
|---|---|
| 第 1~4 次 | 「账号或密码错误」 |
| 第 5 次 | 「密码连续输错 5 次，账号已锁定 15 分钟。忘了密码请联系管理员重置」 |
| 锁定期间 | 「密码连续输错 5 次，账号已锁定，请 N 分钟后再试」 |

四条刻意的取舍：

- **先判锁、再验密码**。反过来的话，密码一旦被猜对，那一刻就是它唯一派上用场的时候——锁了个寂寞
- **锁账号，不锁 IP**。厂内几十台设备走同一个出口，锁 IP 会把整条产线一起锁掉
- **失败不延长锁定期**。延长等于给了别人一个「知道账号就能让人上不了班」的开关，固定 15 分钟把伤害封顶
- **锁定同时把计数清零**。留着 5 的话，期满后只要再错一次就立刻重新锁定，号码等于永久废掉

> 已知的取舍：锁定提示会说清「这个账号被锁了」，因此拿一个不存在的账号去试，得到的是
> 「账号或密码错误」。也就是说，**连错 5 次之后能判断出账号是否存在**。
> 这里选择了好用：厂内账号是人名拼音，本来就不难猜，而「登不上又不告诉为什么」
> 会让每个人都要打一次电话。真正的防线是不让人猜出密码，不是不让人知道账号存在。

> 另一个刻意的细节：账号不存在时也会跑一次 BCrypt（拿常量哈希空转）。不这么做，
> 登录接口的响应时间就成了「这个账号存不存在」的探针——不存在的直接返回，存在的要算几十毫秒。

**管理员解锁**
```
POST /auth/users/7/unlock     →  body 为空
```
锁本来会自己解开，这个接口是给「等不了」的场景：人站在产线旁打电话说登不上。
重置密码、编辑账号时填了新密码，都会顺带解锁——否则会出现「密码已重置但人还是登不进去」。

**前端的会话怎么存（两端不共用）**

`token` 与 `user` 都存在浏览器的本地存储里，**PC 与移动端各用一套键**：

| 端 | token | user |
|---|---|---|
| PC（`/`） | `qc_token` | `qc_user` |
| 移动端 H5（`/h5/`） | `qc_h5_token` | `qc_h5_user` |

不能共用的原因是**序列化格式天生不搭**：uni-app 的 `uni.setStorageSync` 存对象时会包一层
`{ "type": "object", "data": {...} }`，而 PC 端直接 `JSON.parse` 拿到的就是这个外壳，
`user.role` 永远是 `undefined`。token 是字符串所以不包装——于是接口照样调得通，
**故障现象只是「右上角写着未知角色、侧栏管理员菜单整块消失」**，最难怀疑到存储格式上。

两端在同一个域（PC 在 `/`、H5 在 `/h5/`），也就共用同一份 localStorage。以前共用 `qc_user`
时，在同一台电脑上打开过 `/h5/` 并登录，这台电脑的 PC 端就再也认不出角色，反之亦然。

另外 PC 端不把这份缓存当权威：**本地读不到 `role` 而 token 还在时，会调一次 `GET /auth/me`
把用户补回来并写回缓存**。缓存会丢（SPA 内跳转不重挂、清站点数据、两端互相覆盖），
但不会因此让一个有权的人看到半套界面。token 真失效时，401 会清掉会话并把页面送回登录页。

**用户列表返回的锁定字段**

```json
{ "id": 7, "username": "zhangsan", "failedAttempts": 0, "lastFailedAt": "2026-11-09 14:52:11",
  "lockedUntil": "2026-11-09 15:07:11", "locked": true, "lockMinutes": 14 }
```

`locked` / `lockMinutes` 由服务端算好下发，**不要在前端拿 `lockedUntil` 和本地时间比**——
浏览器与服务器差几分钟是常事，差的这几分钟会让「页面显示已锁定、人其实能登」这种矛盾状态出现。

**修改密码**
```json
{ "oldPassword": "admin123", "newPassword": "abc12345" }
```
> 新密码至少 6 位，且不能与原密码相同。

**用户管理**

`role` 只接受 `ADMIN` / `INSPECTOR` / `REVIEWER`，其他值直接拒。系统会拦住「把最后一个可用管理员降级或停用」——一旦没有管理员，标准发布和账号维护就全锁死了，只能改库救回来。

```json
// POST /auth/users 新增
{ "username": "zhangsan", "realName": "张三", "role": "INSPECTOR", "enabled": true, "password": "init123" }

// POST /auth/users 修改（传 id，password 留空表示不改密码）
{ "id": 2, "username": "zhangsan", "realName": "张三", "role": "REVIEWER", "enabled": false }
```

---

## 一、主数据

| 方法 | 路径 | 说明 | 参数 |
|---|---|---|---|
| GET | `/products` | 产品列表 | `keyword` 可选，匹配编号或名称 |
| GET | `/products/overview` | 产品一览：带 `standard_count` / `active_standard_count` / `batch_count` | `keyword` 可选 |
| POST | `/products` | 新增 / 修改产品（仅 ADMIN） | body 见下 |
| GET | `/items` | 检验项列表 | `groupName` 可选 |
| POST | `/items` | 新增 / 修改检验项（仅 ADMIN） | body 见下 |
| GET | `/batches` | 批次列表（带检验进度，见下） | `status` / `productId` / `keyword` 可选 |
| POST | `/batches` | 新增 / 修改批次（ADMIN / INSPECTOR） | body 见下 |

**保存时的前置校验**（错误信息是给人看的，不是把唯一索引的报错原样抛出来）：

| 场景 | 提示 |
|---|---|
| 产品编号为空 / 重复 | 「产品编号不能为空」/「产品编号 X 已被占用，换一个」 |
| 产品名称为空 | 「产品名称不能为空，光有编号看不出这是什么东西」 |
| 检验项编号重复 | 「检验项编号 X 已被占用，换一个」 |
| ENUM 缺 `optionsJson`、JSON 坏、选项缺 code/name、全部不合格 | 「枚举选项格式不对，应为 …」等 |
| BOOL 缺 `boolPassValue` | 「布尔型必须指明合格值：是「有」算合格，还是「无」算合格」 |
| 批次号为空 / 重复 | 「批次号不能为空…」/「批次号 X 已存在，同一批料不要重复登记」 |
| 批次的产品不存在 | 「产品不存在，请先在主数据里建产品」 |
| `inspectType` 不在四类之内 | 「检验类型只能是 IQC / IPQC / FQC / OQC」 |

> 为什么要前置校验：唯一索引抛的是 `duplicate key value violates unique constraint "uk_batch_no"`，
> 车间的人看不懂，只会判断成「系统又坏了」，然后回去用 Excel。

`GET /batches` 每行额外带五个**瞬态字段**（不落库，按 id 聚合现算——进度随时在变，
落库就要有人负责同步，忘一处就是永远错且看不出错）：

```json
{ "id": 12, "batchNo": "B20261026001", "qty": 500, "status": "INSPECTING",
  "lotCount": 2, "sampledQty": 100, "doneQty": 50, "passedQty": 45, "failedQty": 5 }
```

| 字段 | 含义 |
|---|---|
| `lotCount` | 有效检验单数，**不含作废单** |
| `sampledQty` | 抽检件数之和，**含草稿单** |
| `doneQty` | 已判定件数 = 合格 + 不合格；草稿单贡献 0 |
| `passedQty` / `failedQty` | 提交判定后回写的件数 |

口径三条：

- **进度的分母是抽检件数，不是批次数量。** AQL 抽样下抽 50 件检完 50 件就算检完；
  拿 50/500 画进度条永远不满，看着像一直没进展
- **草稿单算进 `sampledQty` 但不进 `doneQty`。** 建了单说明有人已经在检；
  件数要等提交判定才回写，两者之差恰好是「抽了还没判」
- **返工复检算第二单。** 所以 `lotCount` 不能和批次数相减，返工批次会显示「2 单」

一单都没开时五个字段都是 `0` 而不是 `null`——前端拿 0 能直接画进度条，
拿 null 要先判空，判漏一处就是 `NaN%`。

`GET /products/overview` 响应（`active_standard_count = 0` 意味着这个产品开不了检验单）：

```json
[{ "id": 1, "product_code": "P-FLANGE-DN50", "product_name": "法兰盘",
   "spec": "DN50 PN16", "unit": "件",
   "standard_count": 2, "active_standard_count": 1, "batch_count": 12 }]
```

**产品**
```json
{ "productCode": "P-FLANGE-DN50", "productName": "法兰盘", "spec": "DN50 PN16", "unit": "件" }
```

**检验项**
```json
{
  "itemCode": "I-OD",
  "itemName": "外径",
  "valueType": "NUMBER",
  "unit": "mm",
  "defaultMin": 11.95,
  "defaultMax": 12.05,
  "groupName": "尺寸",
  "measureTool": "千分尺"
}
```
枚举型需给 `optionsJson`：
```json
"optionsJson": "[{\"code\":\"NONE\",\"name\":\"无划伤\",\"pass\":true},{\"code\":\"HEAVY\",\"name\":\"严重划伤\",\"pass\":false}]"
```
布尔型需给 `boolPassValue`（录入该值判为合格）。

**批次**
```json
{
  "batchNo": "B20261019001",
  "workOrderNo": "WO-20261019-01",
  "productId": 1,
  "inspectType": "FQC",
  "qty": 100,
  "supplier": "内部生产"
}
```

---

## 二、检验标准

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/standards?productId=1` | 某产品的所有版本，返回 `[{standard, itemCount}]` |
| GET | `/standards/{id}` | 标准详情，返回 `{standard, items}` |
| GET | `/standards/{id}/items` | 只取检验项数组 |
| POST | `/standards` | 新建草稿标准（version 自动为 1，同产品已有草稿会拒绝） |
| PUT | `/standards/{id}` | 修改草稿表头（编号/抽样方式/允收数/备注） |
| DELETE | `/standards/{id}` | 删除草稿（生效过的版本不能删） |
| POST | `/standards/{id}/items` | 整体保存检验项（先清后插，仅草稿可用） |
| POST | `/standards/{id}/publish` | 以该版本为模板创建下一版**草稿**（改完再 activate 生效） |
| POST | `/standards/{id}/activate` | 启用草稿版本（同产品旧 ACTIVE 自动置 OBSOLETE） |
| GET | `/standards/{id}/diff` | 与上一版的判定口径差异，`against` 可指定对比版本 |
| GET | `/standards/stale-usage` | 已作废但仍挂着判定单的版本，`productId` 可选（省略即全库） |
| POST | `/standards/{id}/review` | 给一个作废版本记一次复核（ADMIN/REVIEWER），`{verdict, remark}` |
| GET | `/standards/{id}/reviews` | 该版本的全部复核记录，最新在前 |

`GET /standards/{id}/diff` 响应（质量问题追溯时的第一眼：这一版是不是放宽了）：

```json
{
  "from": { "id": 3, "version": 1, "status": "OBSOLETE", "effectiveFrom": "2026-09-01" },
  "to":   { "id": 9, "version": 2, "status": "ACTIVE",   "effectiveFrom": "2026-10-20" },
  "firstVersion": false,
  "verdict": "RELAXED",
  "verdictText": "整体放宽",
  "advice": "这一版比上一版更宽松。若近期不良流出是在这版之后开始的，优先查这一版",
  "changes": [
    { "itemCode": "I-ID", "itemName": "内径", "field": "maxValue", "label": "上限",
      "before": "12.05", "after": "12.1", "direction": "LOOSEN" }
  ],
  "summary": ["内径 上限 12.05 → 12.1（放宽）"]
}
```

**`verdict` 五档**：`RELAXED` 整体放宽 / `TIGHTENED` 整体收紧 /
`MIXED` 两头都有 / `CHANGED` 改了判定方向但不是宽严 / `NEUTRAL` 无实质变更。
`direction` 只有 `LOOSEN` / `TIGHTEN` 两种，`CHANGED` 用在无法归类的变更上。

方向判定（每条都踩过）：

| 改动 | 方向 | 理由 |
|---|---|---|
| 上限调高 | 放宽 | 能测的值更多 |
| 下限调低 | 放宽 | 同上，方向跟上限相反，容易抄错 |
| 关键项 是→否 | 放宽 | 超差不再判死整批，Ac 能吞掉 |
| 必填项 是→否 | 放宽 | 原来必须填，现在能空着 |
| 测点数 3→1 | 放宽 | 少量两个点 |
| 新增检验项 | **收紧** | 多一道关 |
| 删除检验项 | 放宽 | 少检一样东西 |
| 全检 → 抽检 | 放宽 | 不全检了 |
| 抽样件数 32→8 | 放宽 | 抽得更少 |
| 允收数 Ac 1→3 | 放宽 | 能吞更多不良 |
| AQL 1.5→4.0 | 放宽 | 同批量下 Ac 更大 |
| 布尔合格值反转 | **CHANGED** | 不是宽严问题，是同一份数据结论反过来 |
| 枚举合格选项增删 | 增=放宽 / 减=收紧 / 换一批=CHANGED | — |

两个必须守住的边界：

- **`publish` 产出的是草稿，不是直接生效。** 生效版本一律不可编辑，早先这里直接建成
  ACTIVE，结果「发布新版本」只多了一个版本号，限值一项都改不了
- **全检的 `sampleSize` 恒为 1 是占位值**，不能拿去和抽检件数比。比了会得出
  「全检改抽检反而更严」的荒谬结论，所以只在两边都是 SAMPLE 时才比
- **BigDecimal 用 `compareTo`**：`equals` 会把 12.0 和 12.00 判成不同，
  从 Excel 粘进来的限值小数位从来不统一，那样会造出一堆假差异把真差异淹掉

`GET /standards/stale-usage?productId=` —— **已作废、但仍挂着判定单的版本**（`productId` 可省略，省略即全库）：

```json
{
  "rows": [
    { "product_code": "P-FLANGE", "product_name": "法兰盘",
      "standard_id": 3, "standard_code": "S-FLANGE-01",
      "version": 1, "status": "OBSOLETE", "effective_from": "2026-09-01",
      "current_id": 9, "current_version": 2,
      "inspection_count": 12, "pass_count": 10, "fail_count": 2,
      "last_used_date": "2026-10-20",
      "change_verdict": "TIGHTENED",
      "risk": "HIGH", "risk_text": "合规风险",
      "risk_advice": "标准在这批单判定之后收紧了。判合格的那部分是按更宽松的旧口径放的，按现行标准可能不合格——优先抽查关键尺寸和参数",
      "risk_order": 0 }
  ],
  "versionCount": 1,
  "inspectionCount": 12,
  "failCount": 2,
  "riskLevel": "HIGH",
  "riskText": "合规风险",
  "withoutStandard": 3,
  "note": "有 12 张已判定单据是按已作废的标准版本下的结论，其中 2 张判为不合格；其中 10 张判合格的，是在判定之后标准又收紧了，按现行标准可能不合格，优先查这批"
}
```

改版本身不会回头处理旧单：旧版判过的结论留在库里，它们建立在当时的口径上。
这个接口回答的是「有多少结论已经站在一个不作数的标准上」，以及**要不要去查**。

**`risk` 四档**（数量分不出轻重：42 张旧版单里可能一张都不用管，也可能全是放行风险）：

| 档 | 触发 | 意思是 |
|---|---|---|
| `HIGH` | 旧版→现行**收紧**，且这版下有判合格的单 | 放行按现行标准可能站不住，唯一要抢着查的一种 |
| `WATCH` | 旧版→现行**放宽**且有判 NG 的单；或 `MIXED`/`CHANGED` | 可能误判（返工报废白做），不影响合规但那是钱 |
| `LOW` | 旧版判的全是 NG；或两版无实质差异 | 标准怎么改都不改变这批结论 |
| `UNKNOWN` | 算不出方向（该产品已无 ACTIVE 版、标准项读不出来） | 降级人工核对，**绝不猜方向** |

`risk_order` 是排序权重（`HIGH`0 < `WATCH`1 < `UNKNOWN`2 < `LOW`3），`rows` 已按它
排好、同档按单数降序——工作台只露前 8 行，第一行必须是该看的那行。

方向**复用 `GET /standards/{id}/diff`**（内部调用 `diff(现行版, 旧版)`），不另写一遍宽严判定：
两边各写一份必然慢慢走偏。diff 抛异常只降级成 `UNKNOWN` 并记 warn 日志，
不让一个坏版本把整页打成 500。

口径五条：

- **只算 `SUBMITTED` / `CLOSED`**：草稿还没定论，不该算「按旧版判的」
- **只算关联得上的**（`qc_inspection.standard_id = qc_standard.id`）：早期数据只存了版本号
  没存 id，关联不上就不硬凑。这部分由 `withoutStandard` 单独报总数——
  只说「能查出来的这些」，会让人以为旧版单就这么点
- **`HAVING count > 0`**：从没开过单的作废版本不占版面
- **`current_version` 取该产品当前 `ACTIVE` 版**，同产品只有一个 ACTIVE
  （唯一索引 `uk_standard_active`），不会把单据行数放大
- **`current_id` 一起返回**：只有版本号算不出改版方向（diff 要 id）

`note` 按**最高风险档**定调，不再用「若收紧过就值得复核」这种要人自己去推的说法：
直说「其中 N 张判合格的，在判定之后标准收紧了，优先查这批」。没有旧版单时也不能
合成一句「未发现」——「没有旧版单」和「有旧版单但一张都关联不上」是两件事，
混在一起会让后者永远没人去处理。

点进明细走 `GET /inspections/page?standardId=`（按版本筛检验单，导出走同一个
`InspectionQuery`，屏幕几行导出的就是几行）。

### 改版遗留的复核记录（V14）

`stale-usage` 只回答「要不要查」，答不了审核员下一句「**发现之后做了什么**」。
没有记录，这条观察永远是一笔未闭环的不符合项。

```json
POST /standards/3/review
{ "verdict": "KEPT", "remark": "抽查这一批唯一的合格单，关键尺寸在公差内，维持原判" }
```

响应回显快照（`inspectionCount` / `riskLevel` / `productName` 都当场从 `stale-usage` 取，
不信任前端传值——复核记录是审计证据，里面的数字必须和系统当时算出来的一致）：

```json
{
  "id": 7, "standardId": 3, "productId": 1, "version": 1,
  "standardCode": "S-FLANGE-01", "productName": "法兰盘",
  "riskLevel": "HIGH", "verdict": "KEPT", "verdictText": "维持原判",
  "inspectionCount": 12, "reviewer": "系统管理员",
  "reviewedAt": "2026-10-28T17:58:12"
}
```

**校验**（三样错一样都不收）：

- `verdict` 只能是 `KEPT`（维持原判）或 `ACTION`（需要处理）。**不提供「已阅」这类档**——
  含糊的结论等于没记
- `remark` 至少 5 个字。写不出「查了什么、凭什么」就不算复核
- 目标的 `status` 不能是 `ACTIVE`：现行版本没有旧版结论可复核
- 该版本必须真的挂着判定单（不在 `stale-usage` 清单里就不该被复核）

**只增不改**：同一版本可以复核多轮，每次一行，不做唯一约束。
「10 月抽查 3 张，结论维持」和「11 月客户投诉后又查一遍，这次发现两张确实超差」
是同一次复核的不同轮次——覆盖掉等于把「当时查过、当时判断没问题」这个事实抹了。
`GET /standards/{id}/reviews` 返回全部记录，最新在前；`stale-usage` 的行里回填的是**最新一条**：

| 字段 | 说明 |
|---|---|
| `reviewed` | 恒有值，`false` 也要显式给出（没复核不能靠字段缺失表达） |
| `review_verdict` / `review_verdict_text` | `KEPT`/`ACTION` 与中文 |
| `review_reviewer` / `review_at` | 复核人展示名 + `YYYY-MM-DD HH:MM` |
| `review_remark` | 那一次写的理由 |
| `reviewedCount` / `unreviewedCount`（顶层） | 「12 个作废版本里已复核 3 个」 |

`reviewer` 存的是**展示名**（`UserContext.username()` 给的就是 `displayName`，与 `createdBy` 一致）：
用户停用后 ID 查不出名字，证据要能自解释。

排序也跟着变：`rows` 先按 `risk_order`，同档内**未复核的排前面**，再按单数降序——
工作台只露前 8 行，第一行必须是「最该看且还没人看过」的那行。

**标准（固定抽样）**
```json
{
  "standardCode": "S-FLANGE-01",
  "productId": 1,
  "samplingPlan": "FIXED",
  "samplingType": "SAMPLE",
  "sampleSize": 5,
  "allowedNgCount": 1,
  "effectiveFrom": "2026-10-19",
  "remark": "法兰盘成品检验标准"
}
```

> `allowedNgCount` 是**允收数 Ac**：本批允许的不合格**件数**，不是不合格项数。
> 抽 5 件、Ac=1，意味着最多容忍 1 件不合格。

**标准（AQL 抽样）**
```json
{
  "standardCode": "S-FLANGE-01",
  "productId": 1,
  "samplingPlan": "AQL",
  "aql": 2.5,
  "inspectionLevel": "II",
  "remark": "按 GB/T 2828.1 抽检"
}
```

> `samplingPlan=AQL` 时 `samplingType` 强制落成 `SAMPLE`，`sampleSize` / `allowedNgCount`
> 不再参与计算——样本量与 Ac 由建单时的**批量**查 `qc_sampling_plan` 得出。
> 保存时就会校验该 `aql + inspectionLevel` 在方案表里是否存在，配错不必等到建单才发现。

## 二·补、抽样方案

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sampling-plans/options` | 方案表里现有哪些 `aqls` / `levels` / `planCodes`，配置页下拉框用 |
| GET | `/sampling-plans?aql=2.5&level=II&lot=300` | 返回 `{rows, resolved?, hint?}`；`rows` 是整张对照表，给了 `lot` 就顺带算出命中档 |

```json
{
  "aql": 2.500,
  "inspectionLevel": "II",
  "planCode": "GB2828-1",
  "rows": [
    { "lotSizeMin": 281, "lotSizeMax": 500, "codeLetter": "H",
      "sampleSize": 50, "acceptQty": 3, "rejectQty": 4 }
  ],
  "resolved": { "lotQty": 300, "codeLetter": "H", "sampleSize": 50, "acceptQty": 3 }
}
```

内置数据：GB/T 2828.1-2012 正常检验一次抽样、一般检验水平 II、AQL 2.5，11 个批量区间
（2~8 到 3201~10000）。建单时批量超出 10000 件会直接报错要求拆批——
宁可让人拆批，也不默默按最大档抽 200 件糊过去。

**保存检验项**（数组，一次提交整个标准的配置）
```json
[
  {
    "itemId": 1,
    "groupName": "尺寸",
    "isCritical": true,
    "valueMode": "MULTIPLE",
    "pointCount": 3,
    "minValue": 11.95,
    "maxValue": 12.05
  },
  {
    "itemId": 5,
    "groupName": "外观",
    "isCritical": false,
    "valueMode": "SINGLE"
  }
]
```

**保存检验项时的校验**（在 `StandardService.saveItems` 里，前端拦不住也会被拒）：

| 规则 | 报错 |
|---|---|
| 至少一个检验项 | 至少要配置一个检验项，否则建单时无项可填 |
| 至少一个关键项 | 全部为非关键项时，任意一项超差都不会判死整批 |
| 数值项必须有下限或上限 | 「外径」是数值项，必须给上限或下限，否则无法判定 |
| 下限 ≤ 上限 | 检查是否填反了 |
| 多点测点数 2~20 | 测点数至少要 2 / 超过 20，实际现场没人会填这么多 |
| 枚举项至少一个 pass=true | 选项里没有任何一个判为合格，这样怎么填都是 NG |
| 破坏性项不能配全检 | 全检意味着每件都验，验完就报废 |

**布尔项合格值可覆写**：`qc_standard_item.bool_pass_value` 三态 —— `null` 沿用 `qc_item` 主数据，`true` 录「有」算合格，`false` 录「无」算合格。
这是必需的，因为「毛刺（有=坏）」和「去毛刺（有=好）」是同一类字段的相反语义。

> 注意：已生效（ACTIVE）的标准不能直接改项，接口会拒绝。必须先 `publish` 出新版本再改。

---

## 三、检验单

### 1. 建检验单

`POST /inspections?operator=张三`

```json
{ "batchId": 1, "inspectorName": "张三", "sampleQty": 5 }
```

只需给批次 ID。后端自动找该产品当前 **ACTIVE** 的标准，把全部检验项和限值**快照**进明细，并按 `sampleQty` 展开成 **件 × 项** 的行。

- `sampleQty` 不传 → 取标准里的 `sampleSize`；还不传 → 1
- 上限 200 件/单，超了报错让人拆批
- 允收数 `acceptQty` 同期快照，标准日后改 Ac 不影响已建的单

响应：
```json
{
  "inspectionId": 1,
  "inspectionNo": "QC202610190001",
  "standardVersion": 3,
  "sampleQty": 5,
  "acceptQty": 1,
  "lineCount": 40
}
```

### 2. 检验单详情

`GET /inspections/{id}`

响应按件组织，每件内再按分组归并：

```json
{
  "inspection": {
    "id": 1,
    "inspectionNo": "QC202610190001",
    "standardVersion": 3,
    "sampleQty": 5,
    "acceptQty": 1,
    "status": "DRAFT",
    "result": "NA",
    "passedQty": 0,
    "failedQty": 0,
    "judgeReason": null
  },
  "pieces": [
    {
      "pieceNo": 1,
      "rows": [
        {
          "id": 1,
          "pieceNo": 1,
          "itemName": "外径",
          "valueType": "NUMBER",
          "valueMode": "MULTIPLE",
          "pointCount": 3,
          "minValue": 11.95,
          "maxValue": 12.05,
          "unit": "mm",
          "isCritical": true,
          "result": "PENDING",
          "measuredValues": null,
          "measuredText": null,
          "judgeReason": null
        }
      ],
      "groups": { "尺寸": [ "..." ], "外观": [ "..." ] }
    }
  ],
  "groups": { "尺寸": [ "..." ] },
  "lineCount": 40,
  "itemCount": 8,
  "pieceCount": 5,
  "locked": false,
  "recheckOfNo": null,
  "standardContext": {
    "standardId": 9, "usedVersion": 3, "usedStatus": "OBSOLETE", "usedEffectiveFrom": "2026-09-01",
    "currentStandardId": 12, "currentVersion": 5,
    "changed": true, "versionsSince": 2,
    "verdict": "RELAXED", "verdictText": "整体放宽",
    "advice": "这一版比上一版更宽松。若近期不良流出是在这版之后开始的，优先查这一版",
    "summary": ["内径 上限 12.05 → 12.1（放宽）"],
    "note": "本单依据 v3 判定，现行已到 v5（整体放宽）"
  }
}
```

- `pieces[]` 是主结构，前端做件切换用
- `groups` 是全部行按组的平铺（兼容旧前端，多件时会重复）
- `locked = true` 表示已提交或已归档，前端应置为只读

**`standardContext`：本单按哪版标准判的，那版后来有没有变。** 明细本身带标准快照，
所以历史判定永远可复现；但「当时那版」和「现在这版」差在哪，只看单据看不出来——
而质量问题追溯时这两句话必须同时在场。方向复用 `/standards/{id}/diff`，
`from` 是本单那版、`to` 是当前 `ACTIVE` 版。

| 字段 | 说明 |
|---|---|
| `usedVersion` | 本单判定时用的版本（读 `qc_inspection.standard_version`，没有则取标准当前值） |
| `usedStatus` | 那版现在的状态；`OBSOLETE` 说明已经不是现行版 |
| `currentVersion` | 该产品当前 `ACTIVE` 版本号 |
| `changed` | 本单那版是否已经不是现行版 |
| `versionsSince` | 中间隔了几版，夹到 ≥0 |
| `verdict` / `summary` | 本单那版 → 现行版的方向与逐项差异 |

三种边界：`standardContext` 为 `null`（这张单没记标准，早期数据，**前端不显示这一块**
而不是显示空壳卡片）；标准记录被删时 `usedStatus = "MISSING"`，单据照样有效、只是没法再比；
产品当前没有 `ACTIVE` 标准时 `currentVersion` 缺省并给出 `note`。对比本身出错也不影响返回
单据——判定结论本身永远有效，不能因为算不出差异就打不开单子。

### 3. 草稿暂存

`POST /inspections/{id}/save`

```json
{ "lines": [ { "lineId": 1, "values": ["12.03", "12.01", "12.02"], "textValue": null } ] }
```

只写实测值，**不判定、不改状态、不写日志**。抽 5 件 × 8 项 = 40 个输入框，没人能一口气填完，切件或误退出时靠它保命。

只有 `DRAFT` 状态可调。

### 4. 提交判定

`POST /inspections/{id}/submit?operator=张三`

```json
{
  "lines": [
    { "lineId": 1, "values": ["12.03", "12.01", "12.02"], "textValue": null },
    { "lineId": 2, "values": ["2.62"], "textValue": null },
    { "lineId": 5, "values": ["LIGHT"], "textValue": null },
    { "lineId": 6, "values": ["false"], "textValue": null },
    { "lineId": 8, "values": [], "textValue": "首件确认" }
  ]
}
```

- 数值型传字符串数组，多点按 `pointCount` 传
- 枚举型传选项 `code`
- 布尔型传 `"true"` / `"false"`
- 文本型 `values` 传空数组，内容放 `textValue`
- **必须一次提交全部件**。少传的行按未录入处理，会判该件 NG

响应（`InspectionOutcome`，两层结构）：
```json
{
  "items": [
    { "lineId": 1, "pieceNo": 1, "itemName": "外径", "pass": true, "reason": null, "incomplete": false },
    { "lineId": 9, "pieceNo": 3, "itemName": "外径", "pass": false, "reason": "第 1 点 12.3 > 12.05", "incomplete": false }
  ],
  "pieces": [
    { "pieceNo": 1, "pass": true,  "ngCount": 0, "criticalNgCount": 0, "reason": "全部检验项合格" },
    { "pieceNo": 3, "pass": false, "ngCount": 1, "criticalNgCount": 1, "reason": "关键项不合格：外径（第 1 点 12.3 > 12.05）" }
  ],
  "ngCount": 1,
  "criticalNgCount": 1,
  "passedQty": 4,
  "failedQty": 1,
  "acceptQty": 1,
  "incompleteCount": 0,
  "pass": true,
  "reason": "不合格 1 件（第 3 件），未超过允收数 1 件。关键项问题：第 3 件 关键项不合格：外径（第 1 点 12.3 > 12.05）"
}
```

**判定规则**：
1. 件：同一 `pieceNo` 任一行 NG → 该件 NG
2. 批：`failedQty <= acceptQty` → 批合格
3. 没有任何检验数据 → 判不合格（空单不能判合格）
4. 关键项 NG 落在允收范围内时结论仍为合格，但 `reason` 必须带关键项提示
5. **`incompleteCount > 0` → 整批直接判不合格，`acceptQty` 不适用**

第 5 条单独说明：不合格分两种，`items[].incomplete` 标出是哪一种。

| | 含义 | 典型原因 | Ac 能不能消化 |
|---|---|---|---|
| `incomplete: false` | 指标做坏了 | 超规、枚举判不合格、布尔方向不对 | **能**。这正是 AQL 抽样在管的事 |
| `incomplete: true` | 根本没法判 | 没录、测点数量不够、填的不是数字、选项对不上、标准没配限值/没配选项 | **不能** |

把两者混为一谈会得出很荒唐的结论：抽 20 件、Ac=3，其中 3 件的关键尺寸**压根没测**，
按件数算正好在允收范围内，整批判合格——一份写着「20 件全部合格」的检验记录，
里面有 3 件根本没量过。所以只要出现数据不完整，直接判不合格并在 `reason` 里
点明第几件哪一项（最多列 5 处，后面以总数收尾），要求补录后重新提交。

> **前端录入时标红只做提示，不参与判定。** PC 与移动端的录入页会用明细行上的
> `minValue` / `maxValue` 快照实时判断并把超规格标红（`utils/specHint.js`），
> 目的是让人当场看见「12.5 是不是把 12.05 打错了」。但合格与否一律以本接口为准：
> 规格快照、闭区间、布尔合格值方向这些完整规则都在后端，前端手上的快照只是副本。


副作用：明细写回 `result` / `judgeReason`，生成 `qc_defect` 不良记录（带件号），单据转 `SUBMITTED`，批次**保持** `INSPECTING`，写一条 `SUBMIT` 日志。

> 批次要等审核通过才转 `DONE`。提交只是「等审核」，这时候就把批次算完成，看板上的合格率会提前把没人审过的数据算进去。

**提交后锁定**。重复提交 `DRAFT` 以外的状态会返回错误，必须先 `reopen`。

### 5. 解锁 / 审核 / 归档 / 作废

| 方法 | 路径 | 前置状态 | 权限 | 说明 |
|---|---|---|---|---|
| POST | `/inspections/{id}/reopen?reason=xxx` | `SUBMITTED` | 登录 | 回 `DRAFT`，`reason` 必填 |
| POST | `/inspections/{id}/review` | `SUBMITTED` | ADMIN / REVIEWER | 审核，`approve` 决定归档或退回 |
| POST | `/inspections/{id}/close` | `SUBMITTED` | ADMIN / REVIEWER | 转 `CLOSED` 归档（跳过审核，仅管理员补录用） |
| POST | `/inspections/{id}/void?reason=xxx` | `DRAFT` / `SUBMITTED` | 登录 | 转 `VOID`，`reason` 必填 |

**审核**
```json
// 通过并归档
{ "approve": true, "comment": "抽样符合标准，量具均在有效期" }

// 退回检验员
{ "approve": false, "comment": "第 3 件硬度只测了一点，标准要求单值，请补测并附量具照片" }
```

规则：

| 场景 | 结果 |
|---|---|
| `approve=false` 且 `comment` 为空 | 报「退回必须填写审核意见」 |
| 审核人是该单的 `inspectorName` 且不是管理员 | 报「不能审核自己录入的检验单」 |
| 管理员审核本人录入的单据 | 放行，但日志 `summary` 标注「管理员代审本人录入单据」 |
| 通过 | 状态 → `CLOSED`，批次 → `DONE`，写 `reviewed_by` / `reviewed_at` / `review_comment` 与 `REVIEW` 日志 |
| 退回 | 状态 → `DRAFT`，`result` 清为 `NA`，件数统计清零，批次 → `INSPECTING`，写 `REJECT` 日志 |

四个动作都写日志。`reason` 为空直接报错——解锁和作废必须说清楚为什么，这是质量记录可追溯的前提。

### 6. 操作日志

`GET /inspections/{id}/logs`

```json
[
  {
    "id": 3,
    "action": "SUBMIT",
    "fromStatus": "DRAFT",
    "toStatus": "SUBMITTED",
    "reason": null,
    "operator": "张三",
    "summary": "不合格 1 件（第 3 件），未超过允收数 1 件",
    "createdAt": "2026-10-19T15:20:11"
  },
  {
    "id": 4,
    "action": "REJECT",
    "fromStatus": "SUBMITTED",
    "toStatus": "DRAFT",
    "reason": "第 3 件硬度只测了一点",
    "operator": "王审核",
    "summary": "审核退回：第 3 件硬度只测了一点",
    "createdAt": "2026-10-19T16:02:40"
  }
]
```

### 7. 分页

`GET /inspections/page?current=1&size=10&status=SUBMITTED&batchId=1&productId=1`

返回 MyBatis-Plus 的 `Page` 结构，`records[]` 里额外带了两个非表字段：

- `batchNo` 批次号
- `productName` 产品名

> 这两个字段由控制器一次性补齐（按批次的 productId 批量查），不是为了好看——前端列表每行都发一次请求查批次，十个分页就是几十次往返。

全部筛选参数：

| 参数 | 说明 |
|---|---|
| `status` | `DRAFT` / `SUBMITTED` / `CLOSED` / `VOID` |
| `result` | `PASS` / `FAIL` / `NA` |
| `batchId` / `productId` | 精确匹配 |
| `keyword` | **同时模糊匹配检验单号和批次号**——主管找人时手里通常只有一个号，记不清是哪一种 |
| `inspector` | 检验员姓名模糊匹配 |
| `from` / `to` | 日期区间（`yyyy-MM-dd`，含首尾），按 `coalesce(inspected_at, created_at)` 过滤 |
| `current` / `size` | 分页 |

> 日期为什么用 `coalesce`：草稿还没有检验时间，但翻旧账时也得能被捞出来，
> 否则草稿在列表里是隐形的。
>
> 这套条件定义在 `dto/InspectionQuery` 里，**列表分页和 CSV 导出共用同一个 `toWrapper()`**。
> 以前两处各写一遍，加条件时只改了一处，就会出现「屏幕上 3 条、导出的 CSV 里 30 条」——
> 这种账对不上，比没有导出更糟。

---

## 四、统计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/inspections/stat/daily` | 近 30 天检验量、单级与件级合格数 |
| GET | `/inspections/stat/defect` | 缺陷分布（帕累托数据源） |
| GET | `/batches/stat/supplier` | 供应商来料质量排名（按件级合格率升序，差的在前） |

`stat/supplier` 响应：
```json
[{ "supplier": "宁波精铸一厂", "batches": 12, "lots": 14, "lots_pass": 11, "lots_fail": 3,
   "piece_total": 1240, "piece_pass": 1198 }]
```

> IQC 真正要回答的不是「今天合格率多少」，而是「哪家厂的货在拖后腿」，所以排序朝下。
> 没填供应商的批次归到「（未填供应商）」单独一行，不丢弃——这行数字本身就是个待办。

`stat/daily` 响应：
```json
[{ "day": "2026-10-19", "total": 12, "pass_cnt": 11, "piece_total": 48, "piece_pass": 45 }]
```

- 单级合格率 = `pass_cnt / total`
- 件级合格率 = `piece_pass / piece_total`（抽检场景下用这个，更准）
- 作废单（`VOID`）不计入统计

`stat/defect` 响应：
```json
[{ "defect_name": "硬度", "cnt": 3, "qty": 3 }]
```

---

## 五、量具

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/gauges?status=ACTIVE` | 量具台账 |
| GET | `/gauges/expiring?days=30` | 已过期 + N 天内到期（只查 `ACTIVE`） |
| POST | `/gauges` | 新增 / 修改（`nextCalibrateDate` 由后端算，不用传） |
| POST | `/gauges/{id}/calibrate` | 登记一次校准，JSON body 见下 |
| GET | `/gauges/{id}/calibrations` | 校准履历，按校准日倒序 |
| GET | `/gauges/checks/pending` | 本月还没点检的量具（只含在用且校准未到期的），按「多久没点检」倒序 |
| GET | `/gauges/{id}/checks` | 点检履历，按点检日倒序 |
| POST | `/gauges/{id}/check` | 登记一次点检，JSON body 见下 |
| POST | `/gauges/{id}/scrap?reason=跌落损坏` | 报废 |

**状态**：`ACTIVE` 可用 / `SUSPENDED` 停用（校准不合格或点检不合格，看 `suspendReason`）/ `SCRAPPED` 已报废。

### 登记校准

```json
{
  "calibrateDate": "2026-10-22",
  "cycleDays": 180,
  "result": "PASS",
  "certificateNo": "JL-2026-0888",
  "agency": "市计量院",
  "remark": "维修后复校"
}
```

`result` 取 `PASS` 合格 / `FAIL` 不合格 / `LIMITED` 限用，不传按 `PASS`。

四条规则（都是现场会遇到的）：

| 情形 | 行为 | 理由 |
|---|---|---|
| 同一天重复登记 | 拦 | 手抖点两下、两人同时录，履历会长出两条一样的记录，审核说不清哪条真 |
| 补录更早的日期 | 只写履历，**不动主表有效期** | 证书晚到一个月是常态，补录不该把在有效期的量具改回过期 |
| `FAIL` | 不顺延有效期，`status` 置 `SUSPENDED` | 校准不合格还顺延，第二天就有人拿它测活 |
| `LIMITED` | `remark` 必填 | 不写清范围，下一个人会拿它测不该测的尺寸 |

`SUSPENDED` 的量具修好后重新登记校准，结果 `PASS` 即自动恢复 `ACTIVE`，同时清空 `suspendReason`。
`POST /gauges` 建档、以及编辑时改「上次校准」日期，都会自动补一条履历
（备注分别注明「建档时」「编辑时修改」），保证主表与履历不脱账。

### 登记点检（期间核查）

校准是外部机构一年一次，点检是车间每月拿标准件对一遍。两件事分开存：
校准有证书号与机构，点检只有「对标准件量出来多少」。

```json
{
  "checkDate": "2026-11-06",
  "standardNo": "STD-10",
  "standardValue": 10,
  "measuredValue": 10.01,
  "tolerance": 0.01,
  "remark": "选填"
}
```

响应体带上更新后的量具，前端不用再查一次就知道有没有被停用：

```json
{
  "check": { "result": "PASS", "deviation": 0.0100, "tolerance": 0.0100, "...": "..." },
  "gauge": { "id": 3, "status": "ACTIVE", "suspendReason": null }
}
```

| 情形 | 行为 | 理由 |
|---|---|---|
| 偏差 = 允差 | 判 `PASS` | 边界闭区间，与判定引擎一致 |
| 偏差 > 允差 | `result=FAIL`，量具置 `SUSPENDED`，`suspendReason` 写「点检不合格 日期（偏差 x，允差 ±y）」 | 点检的意义就是发现漂移，发现了还让它干活等于没查 |
| 没填标准件编号 | 拦 | 没有标准件的「点检」只是又量了一遍，证明不了任何事 |
| 同一天重复登记 | 拦（`uk_gauge_check_day` 部分唯一索引兜底） | 手抖点两下不能长出两条一样的记录 |
| 量具已停用 | 拦，并指出原因 | 停用期间再点检只是往履历里堆噪声，该做的是送检复校 |

`deviation` 与 `tolerance` 都按 `numeric(18,4)` 落库、参与比较前先 `setScale(4, HALF_UP)`。
不这么做，`10.01 - 10` 在 double 里是 `0.010000000000000675`，前端会算成超差。
移动端 `common/gaugeCheck.js` 是同一套算法，测试会扒这段 Java 源码比对比较符与取整方式。

### 提交检验单时的校验

每个 `line` 可带 `gaugeId`，带了就会校验，四种情况都拦：

- 已报废 → `选用的量具 G-00x 已报废，不能用于检验`
- 已停用 → `已停用：<suspendReason>。维修并重新校准合格后才能再用`（原因可能是校准，也可能是点检）
- 无校准记录 → `没有校准记录，不能用于检验`
- `nextCalibrateDate < 今天` → `已于 yyyy-mm-dd 到期，超期量具测出的数据不可采信。请先送检校准`

`gaugeId` 当前是**选填**：不填不校验。接真实客户时建议在标准层把数值项标记为「必须记量具」，再改成强制。

**明细里带了 `measureTool`**：建单快照时把 `qc_item.measure_tool`（卡尺 / 千分尺 / 硬度计）一起复制进 `qc_inspection_line`。移动端据此在数值项旁显示「建议 千分尺」，并把类型匹配的量具排在下拉前面——检验员手上可能同时有三四把量具，按项推荐能少翻一半台账。

---

## 六、不良处置

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/defects?inspectionId=1&status=PENDING` | 不良列表 |
| POST | `/defects/{id}/dispose` | 提交处置 |
| POST | `/defects/{id}/concession` | 让步接收审批 |
| POST | `/defects/{id}/recheck` | 挂一张**已有**的复检单（补录用） |
| POST | `/defects/{id}/recheck/open` | **开复检单**：按返工件数自动建单并回填关联 |
| GET | `/defects/pareto?productId=1&from=2026-07-01` | 不良帕累托（含占比与累计占比） |
| GET | `/defect-codes?category=外观` | 不良代码字典 |
| POST | `/defect-codes` | 新增 / 修改不良代码 |

**处置请求**
```json
{ "disposition": "REWORK", "status": "PROCESSING", "note": "返工工序：二次车削", "operator": "张三" }
```

状态流转规则（`DefectService.dispose`）：

| 当前 | 允许去向 |
|---|---|
| `PENDING` | `PROCESSING` / `DONE` |
| `PROCESSING` | `DONE` |
| `DONE` / `WAIVED` | 不可再变（已闭环） |

额外两条硬规则：

- 返工单 → `DONE` 时**必须挂着一张复检单，且该单已判定 PASS**。挂单还不够：
  复检单没提交、判定不合格的，一律不放行——返工没修好却标成已完成，
  就是在质量记录上写「已处理」，而实物还是坏的
- `disposition = CONCESSION` 走 `dispose` 会被拒，必须走 `/concession` 并带理由和授权人（DB 层还有 CHECK 约束兜底）

**返工复检闭环**

```
判 NG → 不良记录（disposition 预填 REWORK / PENDING）
  → 开始返工（PROCESSING）
  → 开复检单（recheck/open）
  → 填报 → 提交 → 审核
  → 复检 PASS → 完成返工（DONE）
  → 复检 FAIL → 不许 DONE，重新返工或改判报废
```

`POST /defects/{id}/recheck/open` 无请求体，返回：
```json
{
  "inspectionId": 12, "inspectionNo": "QC202610210004",
  "sampleQty": 3, "acceptQty": 0,
  "originInspectionId": 9, "originInspectionNo": "QC202610210002",
  "lineCount": 3
}
```

三个与普通建单不同的取值，都是有意的：

| 项 | 取值 | 原因 |
|---|---|---|
| 件数 | 该不良的 `defectQty` | 返工 3 件就验 3 件。按批量重新抽 50 件，抽的是早已判过的那批，验了也证明不了返工有效 |
| 允收数 Ac | **恒定 0** | 这不是抽样验收，是返工有效性确认。给一个允收数，等于允许返工后还有不合格件流出去 |
| 明细判据 | 复制**原单快照** | 标准若已改版，复检和原单就不是同一套判据，两级结论没法对比 |

其它约束：

- 返工未开始（`PENDING`）不许开复检单——没返工就复检，验的还是原来那批坏件
- 一条不良只许一张**有效**复检单（`status <> 'VOID'` 的部分唯一索引兜底）。复检单开错作废后可以重开，不会被永久卡死
- 批量上限 200 件，超过要求按工位/班次拆单

复检单在 `qc_inspection` 上有 `recheck_of_id`（源单）与 `recheck_defect_id`（针对哪条不良），
详情接口额外返回 `recheckOfNo`（源单号），检验单页与报告打印都会标注「返工复检单」。

**让步接收**
```json
{ "reason": "超差 0.02mm，客户确认不影响装配", "approvedBy": "李经理" }
```

**帕累托返回**
```json
[{ "code": "D-SCRATCH", "name": "划伤", "qty": 12, "occ": 5, "percent": 40.0, "cumulative_percent": 40.0 }]
```
> 只统计 `SUBMITTED` / `CLOSED` 的单据，草稿里的不良没过审，算进去会把排名带偏。

**`productId` 与 `from` 都可省略**，省略即「不按这个条件筛」。`from` 接受
`2026-07-29` / `2026-07-29T10:00:00` / `2026-07-29T10:00:00Z` 三种写法，
传空串等于不传，格式不认识会返回中文提示而不是 PG 的英文报错。

> 时间基准是 `coalesce(inspected_at, created_at)`，与月报 / SPC / 批次进度同口径。
> 用 `created_at` 的话，补录的历史单会被算到录入那天，帕累托和月报对不上。

> **这两个参数是可选的，但 SQL 里必须写成 `CAST(#{x} AS bigint) IS NULL`。**
> 直接写 `#{x} IS NULL` 时 `$1` 没有类型上下文，PG 会报
> `could not determine data type of parameter $1`，而这个页面恰好只传 `from`
> 不传 `productId`——于是「可选参数」变成了「一打开就 500」。

---

## 七、追溯与出货

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/trace/batch/{batchId}` | 正向：批次 → 检验单 → 不良 → 出货 |
| GET | `/trace/shipment/{shipmentNo}` | 反向：出货单号 → 批次 → 检验单 → 量具与实测值 |
| GET | `/trace/customer?customer=苏州德茂` | 某客户收到过的所有批次 |
| GET | `/shipments?batchId=1` | 出货记录（不分页，按批次/客户过滤） |
| GET | `/shipments/page?current=1&size=10` | 出货记录分页：`keyword`（单号/客户/批次号）、`status`、`from`、`to` |
| GET | `/shipments/context/{batchId}` | 出货前置信息：批次数量、已出货、还能出、最新检验结论、拦截理由 |
| POST | `/shipments` | 登记出货（带校验） |
| POST | `/shipments/{id}/void` | 作废（红冲）出货单，`reason` 必填 |

### 出货登记

```json
{
  "batchId": 1,
  "customer": "苏州德茂机械",
  "qty": 300,
  "shippedAt": "2026-10-21T10:00:00",
  "remark": "客户催货，让步放行已由质量经理批准",
  "force": true
}
```

`force` 是「例外放行」开关。以下三条会把出货拦下来，只有 `force=true` 才能过：

| 拦截条件 | 提示 |
|---|---|
| 该批次最新检验结论为 `FAIL` | 该批次最新检验结论为不合格（单号） |
| 该批次最新检验结论为 `NA`（提交了但没判定） | 该批次检验单尚未判定（单号） |
| 该批次没有任何已提交/已归档的检验单 | 该批次还没有任何检验结论 |

另外两条**任何情况下都不放行**（`force` 也过不去）：

- **客户为空** —— 没有客户名字，这张出货记录在客诉时等于没用
- **累计超发** —— 批次 100 件、已出 80 件，再报 30 件直接拒绝

> 尺度是「默认关门，允许显式开」：一刀切禁止会逼车间绕过系统手工记账，
> 默默放行则是质量事故的温床。例外放行的记录会在 `remark` 前打上 `[例外放行]`，
> 一年后翻出来能一眼认出它当年是带着问题出去的。

`GET /shipments/context/1` 响应：

```json
{
  "batchId": 1, "batchNo": "B20261019001",
  "batchQty": 500, "shippedQty": 200, "remainingQty": 300,
  "inspectionNo": "QC202610190001", "result": "PASS",
  "blocking": null
}
```

> `remainingQty` 必须由后端算：并发登记时两边都以为还有余量，靠前端自己减会超发。

出货单号格式 `SH + yyyyMMdd + 3 位日流水`（如 `SH20261021001`），和检验单号一样按日计数、删除后不复用。

> **单号由 `qc_no_seq` 计数器分配，不是 `count(*) + 1`。** 后者在两个人同时开单时会分配出同一个号，
> 撞上唯一索引后抛 PostgreSQL 原始报错（`duplicate key value violates unique constraint`）——
> 用户看到的是 500 加一段英文，既看不懂也不知道重试一下就好。唯一索引挡住的只是「写进库」，
> 挡不住「号已经重复分配」这件事本身。
>
> 现在取号走 `UPDATE qc_no_seq SET last_no = last_no + 1 ... RETURNING last_no`，
> 同一前缀的并发请求在行锁上排队，各自拿到不同的值；且与单据插入同事务，
> 回滚时计数一起回滚，不留空号也不重复。检验单（QC）/ 出货单（SH）/ 客诉（TS）共用这一套。

### 出货作废（红冲）

`POST /shipments/{id}/void`

```json
{ "reason": "客户取消订单" }
```

作废**不是删除**：单据留着，`status` 改为 `VOID` 并记下 `void_reason / voided_by / voided_at`。
理由必须写——「客户临时取消」和「压根没发过」在客诉时是两件完全不同的事，删掉就分不清了。

| 情况 | 结果 |
|---|---|
| `reason` 为空或全空格 | 拒绝：作废必须写理由 |
| 非管理员且不是登记人 | 拒绝：只能作废自己登记的单 |
| 单已 `VOID` | 拒绝：不需要重复作废 |
| 两人同时点作废 | 带 `status = 'NORMAL'` 条件更新，只有一个成功，另一个拿到「刚被其他人作废」 |

**作废后批次累计出货量会回退**：`shippedQty` 统计排除 `VOID`，
所以作废掉 100 件之后，批次又能重新出 100 件。不回退的话作废等于白作废，
现场只能改回纸单。

### 出货记录分页

`GET /shipments/page?current=1&size=10&keyword=SH2026&status=VOID&from=2026-10-01&to=2026-10-31`

- `keyword` 同时匹配出货单号、客户、批次号（客诉电话里客户不会报出货单号）
- `status`：`NORMAL` / `VOID`
- 返回记录已补齐 `batchNo`、`productName`，前端不必再为每行发请求

返回结构（`/trace/batch/1`）：

```json
{
  "batch": { "batchNo": "B20261019001", "qty": 3, "supplier": "宁波精铸一厂" },
  "product": { "productCode": "P-FLANGE-DN50", "productName": "法兰盘" },
  "inspections": [
    { "inspection": { "inspectionNo": "QC202610190001", "result": "FAIL", "failedQty": 1 },
      "defects": [ { "pieceNo": 2, "defectName": "硬度不足", "dispositionStatus": "PENDING" } ],
      "openDefects": 1 }
  ],
  "shipments": [ { "shipmentNo": "SH20261019001", "customer": "苏州德茂机械", "qty": 3 } ]
}
```

---

## 八、图片

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/images` | 上传，`multipart/form-data` | 登录 |
| GET | `/images?bizType=INSPECTION&bizId=1` | 某单据的照片列表 | 登录 |
| GET | `/images/{id}/raw?exp=&sig=` | 取图片二进制（本地存储模式） | **凭地址签名** |
| DELETE | `/images/{id}` | 删除照片 | 登录，且见下方三条限制 |

**上传**
```
POST /api/images
Content-Type: multipart/form-data

bizType=INSPECTION
bizId=1
file=@photo.jpg
```

**响应 data**
```json
{
  "id": 12,
  "bizType": "INSPECTION",
  "bizId": 1,
  "fileName": "photo.jpg",
  "contentType": "image/jpeg",
  "fileSize": 284913,
  "url": "/api/images/12/raw?exp=1793600000&sig=9Fq2xLm0Wc7rTb1nVh4JkP",
  "createdBy": "系统管理员",
  "createdAt": "2026-10-19T17:30:12"
}
```

> `url` 两种形态：`local` 存储给 `/api/images/{id}/raw?exp=&sig=`（本服务代理，**必须带签名**），
> `minio` 存储给 7 天有效的预签名地址。
> 只接受 jpg / jpeg / png / webp / bmp，单张 ≤ 10MB。`Content-Type` 按扩展名反推，不采信浏览器上报值。

**为什么 `/raw` 要签名**

`<img src>` 带不了 `Authorization` 头，所以这个口子只能对未认证请求放行。而 `id` 是自增的——
只放行不校验，任何人从 1 开始遍历就能把全厂照片拉走（现场实拍、不良件特写、客户送检样品）。
现在授权改由地址上的 `exp + HMAC-SHA256` 携带，签名绑 `id` 与过期时间：

| 请求 | 结果 |
|---|---|
| 地址来自接口（含 `exp` 与 `sig`），未登录 | 200，正常出图 |
| 不带 `sig` | 拒绝：「图片链接无效或已过期，请刷新页面」 |
| 改 `exp` 想延长有效期 | 拒绝（签名对不上） |
| 拿 A 的签名换 B 的 id | 拒绝（签名绑 id） |
| 超过 7 天 | 拒绝，刷新页面即可拿到新地址 |

签名与 JWT 共用 `JWT_SECRET`。**换密钥等于全部图片地址作废**，刷新页面自动恢复，属预期行为。

**删除照片的三条限制**

照片是判定证据，删了存储里就没了（硬删，不保留双份），所以：

- **单据非 `DRAFT` 一律拒绝**：判为已提交/已归档时，结论连同照片构成证据链。结论已经出去了、
  照片还能被抹掉，等于证据可以被事后修饰。要更正必须先走 `reopen` 退回草稿
- **非管理员只能删自己上传的**：否则谁都能去别人单子上挑照片删
- **删了要留痕**：往该检验单的操作日志写一条 `IMAGE_DELETE`，全局审计检索
  （`GET /api/audit/logs?action=IMAGE_DELETE`）能看到谁在什么时候删了哪个文件。
  量具照片（`bizType=GAUGE`）没有归属单据，落不到操作日志，只记应用日志

---

## 八·补、扫码（拍照识别条码）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/scan/decode` | 传一张图，返回识别出的文本与码型 | 登录 |

```
POST /api/scan/decode
Content-Type: multipart/form-data

file=@label.jpg
```

**响应 data**
```json
{
  "text": "B0012026001",
  "format": "CODE_128",
  "formatText": "Code128",
  "rotate": 0
}
```

**为什么是后端解，而不是前端调 `uni.scanCode`**

`uni.scanCode` 在 H5 端**根本不支持**（官方平台差异表里 H5 那栏就是 ×）——不是 HTTPS 的问题，
给了证书也照样调不起来。浏览器原生的 `BarcodeDetector` 又只有 Chromium 系有、iOS Safari 没有，
而厂里发的手机什么都有。后端用 ZXing 解，跟浏览器、协议、机型都无关，http 下也能用。

**识别是怎么做的**

- 码型：Code128 / Code39 / Code93 / Codabar / ITF / EAN-13 / EAN-8 / UPC-A / UPC-E /
  QR / DataMatrix / PDF417 / Aztec，一维二维一起给
- 依次试长边 2400 → 1600 → 1000，最后才用原尺寸（从大到小）。先 2400 是分寸：
  手机原图 4000px 按原尺寸解一次要几百毫秒，而缩到 2400 对标签上的码几乎无损；
  再往下缩是因为整张桌面拍进去时，缩小反而提高码在画面里的占比、少些噪声；
  原尺寸只在全部缩小版都失败时才值得付那份时间
- 每个尺寸再试 0° / 90° / 270° / 180°——一维码有方向，竖着拍的照片不转就是一片认不出
- 结果里的 `rotate` 是试到第几个角度才认出来的，非 0 说明照片拍竖了
- 文本会清掉控制字符、BOM、零宽空格并 trim（一维码编码器常在末尾补 `\r\n`，
  不清掉就会拿 `"B001\n"` 去查批次，然后报告「批次不存在」）
- 单张 ≤ 12MB、长边 ≤ 6000px；识别不出返回业务错误，文案直接可给现场看

> 这个接口**不落库**：只是「看一眼就扔」，照片不进对象存储、不挂到任何单据上。要留证据走 `/images`。

**移动端怎么用**

`common/lookup.js` 的 `scanCode(onResult)` 封好全流程：拍照/选图 → 压到长边 1600 → 上传 →
用 `common/scan.js` 的 `classifyScan` 判一遍。扫到网址或超过 60 字的内容会明说「扫错标签了」，
不会拿去查批次再报「批次不存在」——车间标签旁边还贴着设备点检码、公众号和供应商网址。

---

## 九、检验报告

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/reports/inspection/{id}` | 检验报告 / 合格证所需全部数据，只读 |

**响应 data**
```json
{
  "inspection": { "inspectionNo": "FQC-20261019-0001", "result": "PASS", "sampleQty": 5, "...": "..." },
  "batch": { "batchNo": "B20261019001", "workOrderNo": "WO-8891", "supplier": "某某钢材" },
  "product": { "productCode": "P-FLANGE-DN50", "productName": "法兰盘", "spec": "DN50 PN16" },
  "pieces": [
    { "pieceNo": 1, "ngCount": 0, "result": "PASS", "rows": [ ... ] },
    { "pieceNo": 2, "ngCount": 1, "result": "FAIL", "rows": [ ... ] }
  ],
  "defects": [ { "pieceNo": 2, "defectCode": "D-OD", "disposition": "REWORK", "status": "PENDING" } ],
  "images": [ { "id": 12, "url": "/api/images/12/raw", "fileName": "photo.jpg" } ],
  "conclusion": {
    "result": "PASS", "pass": true, "final": true,
    "reason": "不合格 1 件（第 2 件），未超过允收数 1 件",
    "sampleQty": 5, "acceptQty": 1, "passedQty": 4, "failedQty": 1,
    "ngCount": 1, "criticalNgCount": 0
  },
  "logs": [ ... ],
  "printedBy": "系统管理员",
  "printedAt": "2026-10-19T17:31:02"
}
```

> `conclusion.final = false` 表示单据还是草稿，判定未生效。**前端必须显式提示**，否则会有人拿草稿当合格证。
> 报告里的限值、实测值、量具全部来自建单时的快照，标准后来改版不影响历史报告。

---

## 十、导出（CSV）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/export/inspections?status=CLOSED` | 检验单台账 |
| GET | `/export/defects?status=PENDING` | 不良台账 |
| GET | `/export/inspections/{id}/lines` | 单张检验单的明细 |
| GET | `/export/pareto?productId=&from=` | 不良帕累托，带占比与**累计占比** |
| GET | `/export/daily-yield` | 近 30 天合格率，单级与件级两个口径 |
| GET | `/export/audit-logs?action=VOID&from=&to=` | 操作日志（**仅管理员**），内审取证用 |

响应 `Content-Type: text/csv; charset=UTF-8`，带 BOM，`Content-Disposition: attachment`，浏览器直接下载。

前端调用要点：

```js
// 必须 responseType=blob
const blob = await http.get('/export/inspections', { params: {}, responseType: 'blob' })
```

> **失败时返回的是 JSON 而不是文件流**。axios 配了 `responseType=blob` 后会把那段 JSON 也包成 Blob，
> 所以前端要先判断 `blob.type` 是否含 `json`，是就解析出 `msg` 提示用户，否则会存下一个内容是报错信息的 `.csv`。

**检验单台账列**：单号 / 批次号 / 工单号 / 产品 / 检验类型 / 抽样方式 / 抽样件数 / 允收数 Ac / 合格件数 / 不合格件数 / NG 行数 / 关键 NG 行数 / 判定结论 / 单据状态 / 检验员 / 检验时间 / 审核人 / 审核时间 / 判定说明

**不良台账列**：检验单号 / 件号 / 不良名称 / 不良代码 / 数量 / 处置方式 / 处置状态 / 处置人 / 处置时间 / 让步理由 / 让步授权人 / 复检单号 / 发现时间

**明细列**：单号 / 件号 / 分组 / 检验项 / 数据类型 / 测量方式 / 下限 / 上限 / 单位 / 实测值 / 合格值(布尔) / 量具 / 检验结果 / 判定说明

> 空结果直接报「没有符合条件的检验单可导出」，不返回一个只有表头的空文件——空文件更容易被误当成「本期零不良」。

---

## 十一、状态值约定

| 字段 | 取值 |
|---|---|
| `sys_user.role` | `ADMIN` 管理员 / `INSPECTOR` 检验员 / `REVIEWER` 审核员 |
| `qc_standard.status` | `DRAFT` 草稿 / `ACTIVE` 生效 / `OBSOLETE` 作废 |
| `qc_standard.sampling_type` | `FULL` 全检 / `SAMPLE` 抽检 / `NONE` 免检 |
| `qc_batch.status` | `PENDING` 待检 / `INSPECTING` 检验中 / `DONE` 已完成 |
| `qc_inspection.status` | `DRAFT` 草稿 / `SUBMITTED` 待审核（锁定）/ `CLOSED` 已归档 / `VOID` 已作废 |
| `qc_inspection.result` | `PASS` / `FAIL` / `NA` |
| `qc_inspection.reviewed_by` | 审核人展示名，由登录态写入，不接受前端传值 |
| `qc_inspection_line.result` | `PASS` / `FAIL` / `PENDING` |
| `qc_inspection_log.action` | `CREATE` 建单 / `SUBMIT` 提交 / `REOPEN` 解锁 / `CLOSE` 归档 / `VOID` 作废 / `REVIEW` 审核通过 / `REJECT` 审核退回 |
| `qc_defect.disposition` | `REWORK` 返工 / `SCRAP` 报废 / `CONCESSION` 让步接收 / `RETURN` 退货 |
| `qc_defect.disposition_status` | `PENDING` 待处理 / `PROCESSING` 处理中 / `DONE` 已完成 / `WAIVED` 已让步 |
| `qc_gauge.status` | `ACTIVE` 可用 / `SCRAPPED` 已报废 |
| `qc_item.value_type` | `NUMBER` / `ENUM` / `BOOL` / `TEXT` |
| `qc_standard_item.value_mode` | `SINGLE` 单值 / `MULTIPLE` 多点 |
| `qc_batch.inspect_type` | `IQC` 来料 / `IPQC` 过程 / `FQC` 成品 / `OQC` 出货 |

---

## 十二、待办

手机端首页角标、移动端待办页和 PC 工作台都从这里取数。六个数字一次给全，避免前端为角标发六次请求。

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/todo/summary` | 见下 |
| GET | `/todo/reviews?limit=50` | 待审核的检验单（`SUBMITTED`），已补 `batchNo` / `productName`；`limit` 默认 50、上限 200 |
| GET | `/todo/defects` | 待处置不良（`PENDING` + `PROCESSING`），带 `inspectionNo` / `batchNo` |
| GET | `/todo/complaints?limit=50` | 未关闭客诉，`HIGH` 排最前，已补 `batchNo` |
| GET | `/todo/mine` | `{ drafts: [...], rejected: [...] }`，按登录人过滤 |
| GET | `/todo/gauges` | 超期未校准量具，带 `overdueDays` |

`/todo/reviews` 的 `limit` 是给工作台用的（只铺头部几条）；要全量翻页去
`/inspections/page?status=SUBMITTED`。`/todo/defects` 带 `batchNo` 是因为处置人要拿着批次号
去现场找料，只给检验单号还得再点进单据看一眼。

`/todo/summary`：

```json
{
  "pendingReview": 3,
  "pendingDefect": 5,
  "overdueGauge": 1,
  "myDraft": 2,
  "myRejected": 1,
  "openComplaint": 2,
  "actionable": 11
}
```

口径说明：

- **待审核**只算 `SUBMITTED`。草稿是检验员自己的活，不该出现在审核员待办里
- **被退回**的判定是「当前是草稿 + 历史上出现过 `REJECT`」。改完重新提交后状态变 `SUBMITTED`，自然从待办消失，不需要已读标记
- **`actionable`** = 待审核 + 待处置 + 我被退回 + 未关闭客诉，**不含**量具超期：量具不是单据，混进去会让人误以为积压了几十张单
- 量具超期单独立一项。超期量具量出来的数据，体系审核直接判无效，它比「有几张单待审」更要紧
- 客诉计入 `actionable`：客户已经投诉了、还没给答复，这是最不能压的一件事

---

## 十三、操作日志（审计）

单据详情页的轨迹回答「这张单发生过什么」；这里是反过来的问题——「谁在上个月作废过单」、
「已归档的单被谁解锁过」。数据一直在 `qc_inspection_log` 里，此前没有窗口。

**仅管理员**：日志里有作废理由、退回意见，等于把争议记录摊开，不该随手可翻。
单据自己的轨迹（`GET /inspections/{id}/logs`）不受此限。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/audit/logs` | 分页；`action` / `operator` / `keyword` / `from` / `to` 均可选 |
| GET | `/export/audit-logs` | 同条件导出 CSV（上限 5 万行） |

`keyword` 同时匹配**检验单号**和**批次号**——来查的人手里常常只有一个号，
记不清那是单号还是批次号（与 `/inspections/page` 同口径，都用 EXISTS 子查询）。

返回行：

```json
{
  "id": 42, "action": "VOID", "inspectionId": 7,
  "inspectionNo": "QC202610220003", "batchNo": "B20261019001", "productName": "法兰盘",
  "fromStatus": "SUBMITTED", "toStatus": "VOID",
  "reason": "IT 重复建单，作废", "operator": "admin",
  "summary": "...", "createdAt": "2026-10-22T18:05:12"
}
```

`action` 取值：`CREATE` 建单 / `SUBMIT` 提交 / `REVIEW` 审核通过 / `REJECT` 审核退回 /
`REOPEN` 解锁重开 / `CLOSE` 归档 / `VOID` 作废。
`inspectionId` 是留给前端「点一下跳回原单」的——只给单号却点不进去，查问题时还得手工复制粘贴。

---

## 十四、站内通知

审核退回、让步放行这类**需要对方动一下**的事件落在 `qc_notice` 里，由后端主动写入，
接收人下次进 App / 打开 PC 就能看到。不做全量消息中心：纯告知的推送会稀释角标，
吵几次之后没人再看，真正要紧的退回反而被淹没。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notices?unreadOnly=false&limit=20` | 我的通知，未读优先、新的在前；`limit` 上限 100 |
| GET | `/notices/summary` | `{ "unread": 2 }`，角标轮询只用这一个接口 |
| POST | `/notices/{id}/read` | 标记已读；不是自己的通知一律返回「通知不存在」 |
| POST | `/notices/read-all` | 全部已读，返回 `{ "updated": n }` |

`noticeType` 取值：

| 值 | 触发点 | 接收人 |
|---|---|---|
| `REVIEW_REJECT` | 审核退回（`InspectionService.review`，`approve=false`） | 检验单 `created_by` |
| `CONCESSION` | 让步接收审批（`DefectService.concession`） | 不良记录 `created_by` |

`bizType` / `bizId` 用于点击跳转：`INSPECTION` 进检验单，`DEFECT` 进不良处置。

写通知的三个约束：

- **接收人为空或 `anonymous` 不写**，否则定时任务这类无登录态场景会攒出一堆空通知
- **接收人就是操作人不写**（例如管理员代审自己的单），自说自话的提醒没有意义
- **不吞异常**：宁可让退回失败，也不要出现「单子退回去了但没人收到通知」——
  后者在体系上等于异常未通报，比操作失败更难发现。通知与业务主事务一起提交

前端取数方式：PC 顶栏铃铛每 60 秒轮询 `/notices/summary`（失败静默，不弹错误框），
点开时才拉列表；移动端在待办页顶部显示未读横幅，进「通知」页看列表。

### 企业微信推送（可选）

站内通知有个绕不开的盲点：**人不在系统里就收不到**。退回写进了库，检验员在车间，
他不会坐在电脑前刷角标，单子能压三天。所以同一条通知会顺带发到企业微信。

不配也能用——推送是增强，不是主链路的启动前提。三样凭证（`WECOM_CORP_ID` /
`WECOM_AGENT_ID` / `WECOM_SECRET`）齐了并且 `WECOM_ENABLED=true` 才会真的发；
缺任何一样都是「通道未启用」，站内通知照旧。

推送状态落在 `qc_notice` 上，`pushState` 五个值：

| 值 | 含义 | 该谁处理 |
|---|---|---|
| `NONE` | 通道没开，或历史数据 | 不用管 |
| `PENDING` | 已入队，还没发出去 | 等几秒；一直不动看服务日志 |
| `SENT` | 企业微信回了 `errcode=0` | — |
| `FAILED` | 发了但失败：网络、凭证、IP 白名单、UserID 不存在 | 看 `pushError` 原文 |
| `SKIPPED` | 压根没发：**接收人没绑企业微信账号** | 去「用户管理」补账号 |

`FAILED` 与 `SKIPPED` 必须分开，因为该找的人不一样：一个查凭证与可信 IP，一个补账号映射。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/system/notice-push?recent=10` | 推送健康：`{enabled, missing, states, problems}` |
| POST | `/system/notice-push/{id}/retry` | 补好账号后重发一条；返回 `{queued, message}` |

两个接口都只给 `ADMIN`：修凭证、补账号都是管理员的事。

三条实现约束：

- **推送不在事务里做**。事务提交后（`AFTER_COMMIT`）才派发，异步线程里发 HTTP，
  失败只改 `push_state`。把两者绑一起的后果是「企业微信抖一下，审核员点不动退回按钮」
- **接收人靠人工绑定**，不按姓名自动匹配通讯录：工厂里重名不是小概率事件，
  自动匹配歪了就是把别人的退回意见推给不相干的人，比不推更糟。
  一个企业微信账号只能绑一个人（`uk_user_wecom_userid`），否则一条退回会发两遍
- **消息是纯文本**，不是图文卡片：卡片要配 HTTPS 跳转地址，而这个系统目前只有 IP 直连。
  配了 `APP_PUBLIC_URL` 才在末尾带一句手机端入口，没配就不给人一个点不开的地址

---

## 十五、客户投诉

追溯链原来到「货发给了谁」就断了：客户把不良品退回来的时候，系统里没有地方能记下这件事。
客诉只活在电话和微信里，年底体系审核问「今年投诉几起、关闭率多少、根因分布」——一条都拿不出来。
`qc_complaint` 补上 PDCA 里缺的 A（处置）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/complaints/page` | 分页；`status` / `severity` / `keyword` / `from` / `to` / `open` 均可选 |
| GET | `/complaints?batchId=` | 某批次的全部客诉（追溯页嵌入用） |
| GET | `/complaints/summary` | `{ "high": 2, "medium": 5, "low": 1, "open": 4 }` |
| POST | `/complaints` | 登记客诉 |
| POST | `/complaints/{id}/start` | 受理：`OPEN` → `PROCESSING` |
| POST | `/complaints/{id}/close` | 关闭；**ADMIN / REVIEWER** |
| GET | `/todo/complaints?limit=50` | 工作台用：未关闭客诉，`HIGH` 排最前 |
| GET | `/export/complaints` | 同条件导出 CSV（上限 5 万行） |

登记请求体：

```json
{
  "batchId": 12, "shipmentId": 34,
  "customer": "苏州某机械", "complaintDate": "2026-10-22",
  "category": "SIZE", "severity": "HIGH", "qty": 3,
  "description": "外径超差，装配后卡死", "remark": "客户要求换货"
}
```

关闭请求体（三项都必填）：

```json
{ "rootCause": "车刀磨损未按周期更换", "action": "换刀频次由 500 件改为 300 件，首件必测", "responsibility": "本厂制程" }
```

状态机很短：`OPEN` 待处理 → `PROCESSING` 处理中 → `CLOSED` 已关闭。

**登记门槛**（全部硬拦，不提供跳过开关）：

| 条件 | 原因 |
|---|---|
| `batchId` 必填且批次存在 | 没有批次的客诉无法追溯也没法定责，只能算抱怨 |
| `customer` 必填 | 没有客户名字，这张记录以后没法核对 |
| `description` 必填 | 不写现象，根因分析无从下手 |
| `complaintDate` 不晚于今天 | 未来日期的客诉会把统计和超期计算全打乱 |
| `category` ∈ `APP` 外观 / `SIZE` 尺寸 / `PERF` 性能 / `PACK` 包装 / `MARK` 标识 / `OTHER` 其他 | 类别是质量体系的固定用语，不现场自造 |
| `severity` ∈ `HIGH` 严重 / `MEDIUM` 一般 / `LOW` 轻微，缺省 `MEDIUM` | 排序与响应时限都靠它 |
| `qty` 若给必须 > 0 | 客户报不准时留空，别瞎填 |
| `shipmentId` 若给，必须属于该批次 | 关联错批次比不关联更糟——以后按出货单核算客诉会算到别的批次头上 |

**关闭门槛**：`rootCause` / `action` / `responsibility` 三项缺一不可。
要求一登记就写根因，结果一定是「已处理」三个字，那比不填更糟；只在关闭时强制，
才逼得出能拿给审核看的记录。关闭用「带 `status <> 'CLOSED'` 条件的更新」，
两个人同时点只有一个成功，不会互相覆盖根因分析；已关闭的单也不能退回处理中。

**与出货的联动**：某批次存在未关闭客诉时，`POST /shipments` 会拦下并提示
「该批次有 N 条未关闭的客户投诉」。内部不良品有 `qc_defect` 闭环拦着，客户侧同样得拦——
客户已经投诉了这批货、还没查清又发给下一个客户，等于把同一个问题复制出去。
确认要出可以传 `force=true`，备注会自动带 `[例外放行]` 前缀留痕。
`GET /shipments/context/{batchId}` 新增 `openComplaints` 字段供出货弹窗提示。

**追溯**：`GET /trace/batch/{id}` 新增两块——`complaints`（该批次全部客诉）与
`openComplaints`（未关闭条数）。追溯页批次视图里客诉排在出货记录之前。

**待办**：`/todo/summary` 新增 `openComplaint`，并**计入** `actionable`——
客户投诉是最不能压的一件事。`/todo/complaints` 按 `severity` 把 `HIGH` 排最前
（字母序 `HIGH/MEDIUM/LOW` 排不出轻重，所以用原生 `ORDER BY CASE`）。

---

## 十六、过程能力分析（SPC）

判定层回答「这批收不收」，回答不了「这道工序稳不稳」。同样 100 件全过，数据贴着规格限
边缘走和稳稳压在中线，下一批翻车的概率差一个数量级——Cp/Cpk 量的就是这个。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stat/capability/items` | 有数值实测值的「产品 + 检验项」组合，供下拉选择 |
| GET | `/stat/capability` | 过程能力分析结果 |
| GET | `/stat/capability/items` 返回 | `productId / productName / itemId / itemCode / itemName / unit / inspectionCount / sampleCount` |

`/stat/capability` 参数：`productId`、`itemId` 必填，`from` / `to` 为 `yyyy-MM-dd`（默认近 90 天）。
漏传这两个 id 返回中文业务提示而不是 Spring 的英文异常——它们在代码里是 `required = false` 后自己判的。

**为什么维度是「产品 + 检验项」而不是「检验项」**：同一个「外径」会用在多个产品上，
各产品规格上下限不同。混在一起算 Cpk 等于拿两把尺子量同一批数。

**取数口径**（三条都不能松）：

1. 只统计 `SUBMITTED` / `CLOSED` 的单。草稿还在改，把它算进来 Cpk 会随「谁正在录单」跳动
2. 作废单不计入——它不代表任何检验结论
3. **超规值一律计入，不剔除。**剔掉再算 Cpk 等于自己给自己发合格证，而抓出「过程在往外飘」
   正是这个功能的全部意义。超规个数单独报一个字段

**Cp/Cpk 与 Pp/Ppk 的差别**：

| 指数 | 用哪个标准差 | 反映什么 |
|---|---|---|
| Cp / Cpk | 组内 σ̂ = MR̄ ÷ 1.128 | 只看同一张检验单内部相邻测量的抖动 |
| Pp / Ppk | 整体标准差 s | 把批与批之间的漂移也一起算进去 |

移动极差必须在**同一张单内部**相邻测量之间取，跨单相减得到的是「两批之间的漂移」，
混进 σ̂ 会把组内变异算大、Cpk 算小。所以 Cp 好看而 Pp 难看，说明工序能力够、但没控住批次间漂移，
那不是算错。

**判定阈值**（按 Cpk，单边规格时 Cp 无定义只看 Cpk）：

| Cpk | 结论 |
|---|---|
| ≥ 1.67 | `EXCELLENT` 优秀 |
| ≥ 1.33 | `SUFFICIENT` 充足 |
| ≥ 1.00 | `MARGINAL` 勉强 |
| < 1.00 | `INSUFFICIENT` 不足 |
| 无规格限 / 样本不足 2 | `UNKNOWN` 无法判定 |

`notes` 是一组必须让看数的人知道的前提，直接铺在页面上：

- 样本量 < 25 → 提示「Cpk 只能当个大概，别拿去和客户对账」。样本少时标准差本身抖得厉害
- 区间内规格限变过 → 按最后一次快照计算，并提示缩短区间
- 每张单只有一个测量值 → 算不出移动极差，σ̂ 退回用整体标准差，此时 Cp 会等于 Pp
- 有超规值 → 报个数并说明未剔除
- Cpk < 1.33 → 按 GB/T 4091 说明后果

**返回结构**（节选）：

```json
{
  "n": 28, "inspectionCount": 2,
  "lsl": 14.0, "usl": 16.0, "mean": 15.0,
  "stdOverall": 0.101825, "stdWithin": 0.177305, "meanMr": 0.2,
  "cp": 1.88, "cpk": 1.88, "pp": 3.273, "ppk": 3.273,
  "outOfSpec": 0, "outOfSpecRate": 0.0,
  "verdict": "EXCELLENT", "verdictText": "过程能力优秀",
  "notes": ["..."],
  "histogram": { "bins": [{"from": 14.9, "to": 14.95, "count": 7}], "lsl": 14.0, "usl": 16.0, "mean": 15.0 },
  "controlChart": {
    "individual": { "points": [{"seq": 1, "value": 15.1}], "center": 15.0, "ucl": 15.532, "lcl": 14.468 },
    "movingRange": { "points": [{"seq": 1, "value": 0.2}], "center": 0.2, "ucl": 0.653, "lcl": 0 }
  }
}
```

**I-MR 控制图**：I 图上下限 = X̄ ± 2.66 × MR̄（2.66 = 3/d2），MR 图上限 = 3.267 × MR̄。
两张必须一起看——只看 I 图，一个均值不变但突然抖起来的工序会显得完全正常。
`stdWithin` / `stdOverall` 保留 6 位小数，`cp` 等指数保留 3 位；分母为 0（数据几乎无波动）时
返回 `null` 而不是 `Infinity`，免得前端画出个 NULL。

### 控制图判异（GB/T 4091 八准则）

只画控制限不判异，等于没画：一条连续 9 点偏在中心线上侧的曲线，看起来和正常曲线一模一样，
**只有真超出控制限才会被注意到，而那时候批量不良已经做出来了**。所以 `controlChart` 里多两块：

```json
"controlChart": {
  "individual": { "...": "同上" },
  "movingRange": { "...": "同上" },
  "verdict": "WARNING",
  "verdictText": "过程出现异常倾向：点还没超出控制限，但排列已经不像随机波动了",
  "alarms": [
    { "code": "R2", "level": "WARN", "title": "同侧偏移",
      "text": "连续 9 个点都在中心线上方，均值已经整体上移。共出现 1 次",
      "fromSeq": 1, "toSeq": 9, "hits": 1 }
  ]
}
```

| 代码 | 准则 | 窗口 | 级别 | 车间该干什么 |
|---|---|---|---|---|
| R1 | 单点出界 | 1 点超 ±3σ | `CRITICAL` | 停机，挑出这一段批次 |
| R2 | 同侧偏移 | 连续 9 点在同侧 | `WARN` | 查「什么时候开始偏的」 |
| R3 | 持续上升/下降 | 连续 6 点单调 | `WARN` | 刀具磨损、量具漂移、原料批次 |
| R4 | 上下交替 | 连续 14 点交替 | `WARN` | 两台设备/两个班组/两批原料混投 |
| R5 | 接近控制限 | 3 点中 2 点在 ±2σ 外（同侧） | `WARN` | 下一个点很可能就出去 |
| R6 | 外侧聚集 | 5 点中 4 点在 ±1σ 外（同侧） | `WARN` | 查最近有没有换料/换模/换人 |
| R7 | 波动过小 | 连续 15 点全在 ±1σ 内 | `WARN` | 量具分辨力不够，或数据是照上次补填的 |
| R8 | 双侧远离 | 连续 8 点全在 ±1σ 外（两侧） | `WARN` | 两套数据混在一张图上 |

几条刻意的取舍：

- **σ̂ 用的是画控制限的那个**（MR̄ ÷ 1.128）。换成整体标准差 s 会出现「点在限外、判读说无异常」
- **上下两侧分开数**。2 个点在 +2σ 外，和 1 个 +2σ、1 个 −2σ，是两件不同的事
- **同一准则只报一条**，位置取**最近**一次触发的窗口，另附 `hits` 次数。页面关心「现在怎么样」，
  「是老毛病还是偶尔一次」由次数回答；`fromSeq~toSeq` 就是图上要标红的那几个点
- **σ̂ 为 0 判 `UNKNOWN`，不是 `STABLE`**。所有测量值完全一样时 1σ/2σ/3σ 退化成一个点，
  任何比较都没意义；这种数据本身可疑（分辨力不足或补填），但不该由判异规则来指控
- **正好压在 1σ/2σ/3σ 上的点算「不在其中任一区」**（浮点容差 1e-9）。边界归属含糊时，
  宁可少报一次，也不要把「刚好到线」说成「超线」

`verdict` 取最严重的一档：任一条 R1 → `OUT_OF_CONTROL`，其余命中 → `WARNING`，都没命中 → `STABLE`。
纯逻辑在 `com.qc.inspect.spc.ControlRules`，19 个单测钉住每条准则「刚好触发」与「刚好不触发」两侧。

**不需要新增数据库脚本**：数据来自 `qc_inspection_line.measured_values`（jsonb 数组）与
行上的 `min_value` / `max_value` 快照，`idx_line_item` 已能支撑按检验项取数。

## 十七、质量月报

看板回答「今天怎么样」，过程能力回答「这道工序稳不稳」，月报回答的是
**「这个月该给谁发奖金、该让谁写整改」**——ISO 9001 管理评审那张表。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/reports/monthly` | 一个月的质量汇总 + 环比 + 结论文本 |

参数 `month` 为 `yyyy-MM`，**留空取上月**。月报基本都在月头上看，默认当月会是一屏
「本月还没做完」的空数据。格式不对返回中文提示「月份格式应为 yyyy-MM」，不吐
`DateTimeParseException`。

**三条取数口径，改任何一条都会让数字对不上账**：

1. 时间窗是 `[月初 00:00, 下月初 00:00)`，边界固定 `+08:00` 后传给 SQL
   （`CAST(#{} AS timestamptz)`）。不依赖数据库时区，也不依赖服务器的 `now()`——
   同一份月报补跑两次结果必须一致
2. 只算 `SUBMITTED` / `CLOSED`。草稿和作废单不代表任何检验结论，
   算进去会让合格率随「谁今天开了几张单没交」而波动
3. 时间取 `coalesce(inspected_at, created_at)`。历史单的 `inspected_at` 可能为空，
   直接筛它会整段漏掉老数据，表现为「上上个月突然没数据」

返回结构（节选）：

```json
{
  "month": "2026-10", "label": "2026 年 10 月",
  "from": "2026-10-01", "to": "2026-10-31", "prevMonth": "2026-09",
  "hasData": true,
  "current": {
    "orders": 42, "passOrders": 38,
    "pieceTotal": 620, "piecePass": 604, "pieceFail": 16,
    "recheckOrders": 3,
    "yieldRate": 0.9742, "orderYieldRate": 0.9048, "recheckRate": 0.0714,
    "ppm": 25806
  },
  "previous": { "...": "同结构，上月" },
  "delta": { "yieldPp": 1.4, "orderYieldPp": -0.6, "orders": 5, "pieceTotal": 120 },
  "byType": [{ "inspectType": "IQC", "inspectTypeName": "来料检验", "...": "同 current" }],
  "defects": [{ "name": "毛刺", "cnt": 12, "qty": 12, "share": 0.375 }],
  "defectQty": 32,
  "dispositions": [{ "disposition": "REWORK", "name": "返工", "cnt": 9, "qty": 9 }],
  "suppliers": [{ "supplier": "某五金厂", "batches": 3, "lots": 4,
                  "pieceTotal": 200, "piecePass": 180, "yieldRate": 0.9 }],
  "complaints": { "opened": 2, "high": 1, "closed": 1, "openNow": 3, "backlogDelta": 1 },
  "gauges": { "total": 12, "active": 9, "suspended": 1, "scrapped": 2, "overdue": 1,
              "calibrated": { "total": 3, "pass": 2, "fail": 1, "limited": 0 } },
  "narrative": ["2026 年 10 月共完成 42 张检验单...", "..."]
}
```

**`delta` 的单位是百分点，不是百分比。** 96.0% → 97.4% 是「上升 1.4 个百分点」，
说成「上升 1.4%」会被读成不良率改善 1.4%，差一个数量级的理解。

**分母为 0 时比率一律 `null`（序列化后该键不出现），不是 0。** 页面显示「-」。
「这个月没做检验」和「这个月全不合格」在报表上长得一样，含义差一个数量级——
空月输出 `0.0%` 是假警报。同一规则作用于 `delta`：任一期没数据就不给环比，
避免「上升 100 个百分点」。

其他约定：

- `byType` 各行的 `orders` / `pieceTotal` 之和必须等于 `current`，有行落在分组之外
  （如 `inspect_type` 为空）时两侧会对不上，这一条在 IT 里有断言
- `piecePass + pieceFail == pieceTotal`：分子分母对不上说明统计口径串了
- `suppliers` 按件级合格率升序取最差 5 家，本月没来货的供应商不出现
- `gauges` 是**当前快照**，不按月——「现在有几把超期」不需要等月底汇总
- `complaints.backlogDelta = 本月新开 - 本月关闭`，正数说明积压在恶化，
  比单看「未关闭数」有用
- `narrative` 是给管理评审直接念的几句话，顺序：总量与环比 → 主要不良 → 客诉 →
  计量器具 → 返工。措辞规则（空月不假警报、持平不说「上升 0」）由
  `MonthlyNarrativeTest` 逐条锁定

**不需要新增数据库脚本**：全部聚合在既有表的索引上完成。

## 十八、供应商主数据

在此之前，供应商只是 `qc_batch.supplier` 上的一行 varchar，手打进来。同一家厂写成
「宁波精铸一厂」「宁波精铸一厂 」「宁波精铸」会在质量统计里变成三家，供应商合格率排名
裂成三行——每家看着都还行，实际上最差的那家被稀释掉了。V12 把供应商立成主数据。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/suppliers` | 登录用户 | 一览，带引用批次数与件级合格率 |
| GET | `/suppliers/options` | 登录用户 | 新建批次的**下拉专用**，只返回在用供应商 |
| POST | `/suppliers` | ADMIN | 新建 / 编辑（带 `id` 即编辑） |
| POST | `/suppliers/{id}/status?status=ACTIVE|DISABLED` | ADMIN | 停用 / 启用 |

**两列并存，不是冗余**：`qc_batch.supplier_id` 用于统计归并，`qc_batch.supplier` 是
**名称快照**。批次是历史事实——建批那天供应商叫什么名字就该一直叫什么。供应商后来改名
或合并，历史批次不该跟着变，打报告用的是快照。

**建批次时的供应商解析顺序**：

1. 传了 `supplierId` → 校验存在且 `ACTIVE`，名称从主数据取（**前端传的名字会被覆盖**，
   否则会出现「ID 挂的是甲、名字写的是乙」这种查不出来的脏数据）
2. 只传 `supplier` 文本 → 按全称**精确匹配**一次档案，能挂上就挂上（照顾老习惯）
3. 都匹配不上 → 允许保存，`supplier_id` 留空。统计页会把这类批次标成「未建档 N」，
   当成一条待办摆出来，而不是悄悄放过

**停用不删档**：`DISABLED` 只影响 `/suppliers/options` 的可选性，历史批次与统计不受影响。
「这家厂不做生意了」和「这家厂没存在过」是两回事，后者会让历史报表凭空少一块。

校验与口径：

- 名称、编码都唯一（部分唯一索引 `WHERE del_flag = 0`，并发下先查后插挡不住）
- 编码留空自动生成 `SUP%04d`，取**现有最大号 + 1** 而不是 `count + 1`——
  删过档之后 count 会往回退，生成出来的编码撞唯一索引
- `category` 限 `MATERIAL` / `OUTSOURCE` / `PACKAGING` / `SERVICE`
- `level` 限 `A` / `B` / `C`，**可空**。空表示未评级，不等于 C——
  列表上未评级显示「—」而不是灰色的 C
- 供应商合格率的分母为 0（一件都还没检）显示「-」，不显示 0%

`/suppliers` 返回：

```json
[{ "id": 1, "supplierCode": "SUP0001", "supplierName": "宁波精铸一厂",
   "shortName": "宁波精铸", "category": "MATERIAL", "level": "B",
   "status": "ACTIVE", "contact": "张三", "phone": "138...",
   "batches": 6, "pieceTotal": 320, "piecePass": 305 }]
```

**存量数据迁移**（V12 脚本内自动完成，幂等）：把 `qc_batch.supplier` 里去重后的非空文本
刷成档案（编码接现有最大 SUP 号往后编），再按名称回填 `supplier_id`。
迁移建的档 `remark` 里写着「V12 迁移自动建档，非人工录入」——它们缺联系人、类别、评级，
列表上要能一眼看出来还需要人工补全。

**统计口径的变化**：`/batches/stat/supplier` 与月报的供应商块，分组键从
「`btrim(supplier)` 文本」改成「`coalesce(主数据名称, 文本, '（未填供应商）')`」，
并新返回 `unlinked`（未挂档案的批次数）。前端的「未建档」标记就来自这一列。

## 十九、检验员分析

前面几页看的都是「结果好不好」：月报按产品看，过程能力按工序看。这一页按**人**看，
但它回答的不是「谁干得多」，而是 **这些记录站不站得住**。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/stat/inspectors?from=&to=` | ADMIN / REVIEWER | 按检验员聚合 + 记录可信度提示 |

**为什么只给管理员和审核员。** 不是怕检验员看见自己的数，而是这个页面的用法就是
「主管决定去抽查谁的单」——开放给被抽查对象，抽查就变成互相打分。检验员看自己的
数据走检验单列表，那里有全量原始记录。

**这不是绩效排名。** 按不良率给人排座次，结果一定是所有人都学会少判 NG，
数据立刻变好看、也立刻变没用。所以输出里没有任何排名，只有分类提示。

参数：`from` / `to` 都是 `yyyy-MM-dd`，**含首含尾**。留空默认近 90 天
（当月往往只有几张单，谁多谁少全是噪声）。跨度上限 730 天——再往前人早换过几轮了。

返回：

```json
{
  "from": "2026-07-29", "to": "2026-10-26", "days": 90,
  "staff": 3, "baselineNgRate": 0.021,
  "summary": "3 名检验员共提交 42 张单、受检 620 件，件级不良率 2.1%。其中 1 人的记录存在…",
  "total": { "inspector": null, "orders": 42, "pieceTotal": 620, "pieceFail": 13,
             "ngRate": 0.021, "secPerRow": 46.2, "minutesPerOrder": 12.4, "flags": [], "level": "OK" },
  "rows": [{
    "inspector": "张三", "orders": 18, "pieceTotal": 260, "piecePass": 252, "pieceFail": 8,
    "ngCount": 8, "criticalNg": 1, "passOrders": 15, "recheckOrders": 2, "rowTotal": 2600,
    "ngRate": 0.0308, "orderPassRate": 0.8333,
    "secPerRow": 1.4, "minutesPerOrder": 3.4,
    "level": "SUSPECT",
    "flags": ["平均每行 1.4 秒，共 2600 行，难以逐项实测"]
  }]
}
```

**三类信号，阈值都写死在 `InspectorInsight` 的常量里并有单测锁定**
（这类「提示」功能最容易失控：阈值被悄悄放宽到永远不触发，页面一切正常，实际再也不会提醒任何人）：

| 信号 | 触发条件 | 为什么这么定 |
|---|---|---|
| 零不良 | 同组已有人判出不良，此人 ≥ 10 单且一处不良都没有 | 3 单零不良不说明任何事；全组都零不良时也不报——那是产品好，不是记录问题 |
| 录入过快 | 录入行数 ≥ 30 且平均每行 < 2 秒 | 40 行 90 秒提交完，物理上不可能真测。**只报「快」，不报「慢」** |
| 不良率偏离 | 单量 ≥ 5，且不足同组 1/3，或高于 2 倍且不良件 ≥ 3 | 偏低可能是放水，偏高可能是手法/量具差异，也可能判得过细——数字分不出来，要看单 |

`level` 取该人所有提示里最严重的一个：`SUSPECT`（记录可信度）> `WATCH`（仅偏离）> `OK`。

**为什么「慢」不告警。** 现场常见早上开单、下班前补录，`created_at → inspected_at`
天然很长，快慢本身不是证据。只把「快到不可能」当信号，才不会把老实人当嫌疑人。

**口径：**
- 只算 `SUBMITTED` / `CLOSED`，草稿不计——谁正在录单不该影响统计
- 时间取 `coalesce(inspected_at, created_at)`，边界固定 `+08:00`
- 检验员名取登录态展示名（有真实姓名用姓名），不接受前端传值
- `baselineNgRate` 用**全员合计**（含本人），不是「其他人」，也不是各行比率的平均
  （各行平均会被小单带偏：10% 和 5% 平均出 7.5%，实际合起来是 6.7%）
- `secPerRow` / `minutesPerOrder` 的分母为 0 时给 `null` 不给 0
- 合计行的 `secPerRow` 按总量重算，不取各行平均

**录入时长的边界**：`greatest(coalesce(inspected_at, created_at) - created_at, 0)`。
服务器时钟回拨或脏数据会让时长变负，汇总后能把整组平均拉成负数，页面上显示「-3 分钟一行」。

**不需要新增数据库脚本**：全部聚合在 `qc_inspection` 与 `qc_inspection_line` 的既有索引上完成。

## 二十、待检批次（漏检提醒）

前面几页看的都是「检过的结果」：合格率、PPM、Cpk、月报。但**没检的东西不进任何分母**，
所以漏检在报表上完全不可见。来料没检直接投产、成品没检直接出货，等客户投诉回来才发现，
那时追溯链已经断在源头——这是质检体系里最贵的一种漏。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/todo/batches?days=&limit=` | 登录用户 | 建了批次却没有任何有效检验单的批次 |
| — | `/todo/summary` 的 `pendingBatch` | 登录用户 | 需要人动手的那几个（当天建批的不算） |

**判据是「没有任何有效检验单」，不是「批次状态 = PENDING」。** 两者不等价：检验单被作废后，
批次状态还停在 `INSPECTING`，按状态筛会把这种最该被发现的批次漏掉——它看着像「在检」，
实际上一张有效单都不剩。`DRAFT` 算有效：草稿意味着有人已经在动手，这是「没检完」，不是「漏检」。

**四档，从重到轻：**

| level | 含义 | 触发条件 |
|---|---|---|
| `SHIPPED` | 已出货未检 | 有 `NORMAL` 出货记录，却没有有效检验单 |
| `OVERDUE` | 超期未检 | 停留满 `days` 天（默认 2） |
| `DUE` | 待检 | 停留 1 天，未满阈值 |
| `OK` | 正常节奏 | 当天建批 |

**当天建批不报警**：下午到货、下午建批、明天一早检，这是正常节奏。把当天建批也算进去，
页面每天上午挂一排「漏检」，三天就没人看了——一个天天误报的提醒等于没有提醒。

**停留天数从 `coalesce(arrived_at, created_at)` 算**：到货时间才是「这批料在厂里躺了多久」的起点。
补录的历史批次如果 `arrived_at` 填了三个月前，它就该立刻被判超期——那正是没人管的批次。
`greatest(..., 0)` 兜住误填的未来时间，否则负数天数会把这条沉到列表最底，等于把错误藏起来。

**`SHIPPED` 优先于阈值**：`days` 调到 90 也不能把「已经出货没检」洗白。同一行可以同时有
「已出货未检」和「已停留 10 天」两句提示——只报前者会让人以为货是今天刚发的。

返回：

```json
[{
  "batchId": 12, "batchNo": "IT-PB-OLD", "inspectType": "IQC", "qty": 10,
  "status": "PENDING", "supplier": "宁波精铸一厂", "materialBatchNo": null,
  "productCode": "P-FLANGE-DN50", "productName": "法兰盘",
  "arrivedDate": "2026-10-16", "createdDate": "2026-10-16",
  "idleDays": 10, "voidLots": 0, "shipments": 1, "shippedQty": 3,
  "level": "SHIPPED", "urgent": true,
  "flags": ["已出货 1 单共 3 件，却没有一条有效的检验结论", "已停留 10 天，仍未开检验单"]
}]
```

其他约定：

- `days` 夹在 `[1, 90]`：传 0 或负数按 1 天处理，等于把功能关掉会让人以为它坏了
- 排序在服务层做：先按严重度（`SHIPPED` < `OVERDUE` < `DUE` < `OK`），同档按停留天数倒序。
  严重度是业务判断，数据库不认识
- 出货量只算 `NORMAL`：红冲的单没真发货，不能当「已出货」的证据
- `pendingBatch` 计入 `actionable` 总角标——料躺在厂里等人开单，是明确要人动手的一件事
- 单次上限 500 行：漏检批次多到这个量级，说明流程本身出了问题，列表再长也没人逐条看

**配套修掉的一个状态漏洞**：`POST /api/inspections/{id}/void` 作废检验单后，如果该批次
再也没有有效单，批次状态回退到 `PENDING`。以前作废后批次永远停在 `INSPECTING`，
看板显示「在检」、待检列表也捞不到它，这个批次会安静地烂在库里直到出货被拦。

**不需要新增数据库脚本**：`idx_batch_status`、`idx_insp_batch`、`idx_shipment_batch` 已经够用。

---

## 二十一、系统状态（备份健康）

V13 起，`scripts/backup.sh` 每次跑完——成功或失败——往 `qc_backup_log` 写一行。
这一页就是读那张表。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/system/backup-health?recent=10` | ADMIN | 备份新鲜度 + 最近执行记录 |

**为什么值得单独做一个接口。** 备份从 2026-10-20 起每天 02:00 在跑，成功的备份躺在
`/root/qc/backups` 里，但系统里没有任何地方能回答「上次成功是什么时候」。备份坏得很安静：
timer 照样触发、目录照样在（不报错的时候），只有真出事那天才发现前三十天全是空的。
这个接口存在的意义就一句话——让「没有备份」这件事在页面上长得像坏事。

**为什么不读备份目录的文件时间。** 应用跑在容器里，宿主机目录要额外挂卷；一旦换机器、
改路径、或者运维把编排改回简易版，页面就静默失明，而失明的样子和「备份正常」一模一样。
写库则天然跟着库走，恢复之后立刻还能看到之前的备份历史。

**判定分五档**，不是「正常/异常」两分——`STALE` 和 `OVERDUE` 要人做的事完全不同：

| 级别 | 条件 | 含义 |
|---|---|---|
| `OK` | 距上次成功 < 36 小时 | 正常 |
| `STALE` | 36 ~ 72 小时 | 已错过一整轮，报警但还不是故障 |
| `OVERDUE` | ≥ 72 小时 | 连漏多轮，按故障处理 |
| `FAILED` | 最近一次执行状态为 FAILED | 脚本坏了，**优先于**「上次成功还很新」 |
| `NO_RECORD` | 表里一行都没有 | 脚本可能根本没在跑，也可能还没接上 |

**36 小时为什么不是 24。** 计划是每天 02:00，卡 24 小时的话，凌晨 1 点打开页面就会
看到「已 23 小时未备份」的黄色告警——每天固定误报一次，三天就被人当背景噪音关掉。
一个天天误报的灯等于没有灯。

**`FAILED` 优先于新鲜的成功记录。** 今天凌晨成功了、下午手动重跑一次失败，数据是安全的，
但脚本已经坏了，不管的话明天凌晨就是真断。所以照样亮红灯，只是文案里点明
「现有备份还能用」，免得有人半夜被叫起来以为数据没了。

返回：

```json
{
  "level": "OK", "alert": false,
  "flags": ["最近一次成功备份在 15 小时前"],
  "lastStatus": "SUCCESS",
  "lastSuccessAt": "2026-10-26 02:00:11",
  "lastAttemptAt": "2026-10-26 02:00:11",
  "lastMessage": "备份完成 /root/qc/backups/qcinspect-20261026-020000.sql.gz（11264 KB），清理过期 0 个",
  "lastHost": "VM-0-13-ubuntu",
  "hoursSinceSuccess": 15, "hoursSinceAttempt": 15,
  "lastSuccessMb": 11.0, "lastSuccessSizeText": "11.0 MB",
  "runs30d": 7, "failures30d": 0, "totalRuns": 7,
  "recent": [{
    "id": 7, "status": "SUCCESS", "fileName": "qcinspect-20261026-020000.sql.gz",
    "sizeMb": 11.0, "sizeText": "11.0 MB", "prunedFiles": 0, "host": "VM-0-13-ubuntu",
    "message": "备份完成 …（11264 KB），清理过期 0 个",
    "createdAt": "2026-10-26T02:00:11"
  }]
}
```

其他约定：

- `hoursSinceSuccess` / `hoursSinceAttempt` **在库里算**（`now() - created_at`），
  不在应用侧算。写入时用的是 `created_at DEFAULT now()`，同一个时钟源，
  不会因应用机器时区或时钟漂移得出「-3 小时前备份成功」这种鬼话
- 没有成功记录时 `hoursSinceSuccess` 是 `null` 而不是 `0`。写成 0 就会显示成
  「0 小时前刚成功」——这是这个功能最不能犯的错，空表必须看起来像空表
- 时间列在 SQL 里 `to_char` 成字符串：返回 `Map` 时 MyBatis 不做类型映射，
  `timestamptz` 会原样丢成 `Timestamp`，项目的 `TimestamptzTypeHandler` 只作用于
  `LocalDateTime` 目标类型，兜不住这条路径
- `lastSuccessSizeText` / `sizeText` 由后端按量级算好，前端不再自己拼单位：
  不足 1 KB 报字节、不足 1 MB 报 KB、其余报 MB。**一律按 MB 会出问题**——
  空库备份只有 14 KB，换算后四舍五入成 `0.0 MB`，页面上看着像压根没备份成。
  反过来用 KB 表示大库也看不出量级变化（1200 KB 和 340 KB 差着三倍），
  而备份突然变小往往就是 dump 没导全。`lastSuccessMb` 保留给需要数值比较的场合
- `recent` 夹在 `[1, 50]`；`flags` 里的失败原因截到 300 字，避免把整段日志糊在页面上
- 只给 ADMIN：检验员看到「备份 80 小时没成功」什么也做不了，只会在心里多一条关不掉的灯

**脚本一侧的约束**：写库失败绝不能让备份本身失败。库挂掉的时候最需要一份备份，
这时候因为「记不上账」退出，等于在最坏的时刻放弃干活；所以脚本里写库一律吞错误，
只往 stderr 喊一句。`V13` 没跑过时表不存在，脚本照样正常备份，只是页面显示 `NO_RECORD`。

### 磁盘与容量

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/system/storage-health` | ADMIN | 盘容量、数据库大小、照片占用、表体积排行 |

**为什么不只看「用了百分之几」。** 50 GB 的盘用了 90% 还剩 5 GB，什么事都没有；
20 GB 的盘用了 90% 只剩 2 GB，一次 `pg_dump` 就可能顶穿。百分比给人的是错觉，
真正决定「会不会出事」的是**可用字节数**。所以判定以绝对空间为主：
可用不足 3 GB 算 `WARN`、不足 1 GB 算 `CRITICAL`。

**为什么还要算「能撑几天」。** 只说「剩 2 GB」没人会紧张——它听着还挺多。
把最近 30 天新增的照片字节数除以天数，得出「按这个速度还能撑 3 天」，才有人今天就动手。
增速只算照片，不算数据库：日志表和历史单据没有明确的保留期口径，
估出来的日增会随业务波动乱跳，反而误导。

| 级别 | 条件 | 含义 |
|---|---|---|
| `OK` | 可用 ≥ 3 GB 且撑得过 7 天 | 正常 |
| `WARN` | 可用 < 3 GB，或撑不过 7 天 | 还能用，该安排清理 |
| `CRITICAL` | 可用 < 1 GB，或撑不过 2 天 | PostgreSQL 写 WAL 可能失败，当天就得处理 |
| `UNKNOWN` | 读不到盘容量 | **不能当 OK**：容器挂错卷、目录不存在、只读文件系统都是这个样子 |

**`UNKNOWN` 为什么也要 `alert=true`。** 这一栏存在的前提是「读不到就是坏事」。
如果读不到时页面安安静静，那容器挂错卷这种最容易发生的错，表现就是
「一切正常，直到某天所有保存都失败」。

**`path` 必须露在页面上。** 它说明当前监控的是哪个目录。挂错卷的时候，
页面会显示「还有 40 GB」而真实照片盘早已满了——这种「看着正常」的错最难查。

```json
{
  "level": "WARN", "alert": true,
  "flags": [
    "可用 2.4 GB / 共 50.0 GB（已用 95%）",
    "按最近 30 天的存图速度，还能撑 15 天，趁还没紧张先把旧照片清一清",
    "其中照片 8200 张占 12.6 GB，是已用空间的 26%"
  ],
  "path": "/app/data/files", "storageType": "local",
  "totalBytes": 53687091200, "usableBytes": 2576980377, "usedBytes": 51110110823,
  "totalText": "50.0 GB", "usableText": "2.4 GB", "usedText": "47.6 GB", "usedPercent": 95.2,
  "dbBytes": 743440384, "dbText": "709.0 MB",
  "imageCount": 8200, "imageBytes": 13529146982, "imageText": "12.6 GB",
  "recentImageBytes": 4294967296, "recentImageText": "4.0 GB", "recentDays": 30,
  "dailyGrowthBytes": 143165576, "dailyGrowthText": "136.5 MB", "daysLeft": 18,
  "thresholds": {
    "warnUsableText": "3.0 GB", "criticalUsableText": "1.0 GB",
    "warnDays": 7, "criticalDays": 2
  },
  "largestTables": [
    { "tableName": "qc_inspection_line", "sizeText": "312.0 MB", "rows": 184320 },
    { "tableName": "qc_audit_log", "sizeText": "96.0 MB", "rows": 51200 }
  ]
}
```

其他约定：

- `dailyGrowthBytes` / `daysLeft` **估不出来就是 `null`**（最近没存过图、增速低于每天 100 KB、
  或者要十年以上才满），返回体里直接省略。不要在文案里编一个天数——
  「还能撑 12000 天」这种正确的废话会让人从此不看这一栏
- 体积文案统一由后端的 `HumanSize` 算，跟备份那边同一套：不足 1 KB 报字节、
  不足 1 MB 报 KB、其余 MB/GB。各写一份迟早出现「备份 1.2 GB、磁盘 1224 MB」
- `largestTables` 的行数是 `pg_stat_user_tables` 的估算值（依赖 autovacuum 更新），
  只用来让人对量级有感觉，不参与任何判定
- `note` 只在 `storageType != local` 时出现：走 MinIO 时照片不在这台机器上，
  不说这一句，人会把「盘很空」误当成「照片也安全」

**主动提醒。** `StorageHealthService.morningCheck()` 每天 08:30 跑一次，
`WARN` 与 `CRITICAL` 都会给所有启用的管理员推一条站内通知（走 `qc_notice`，
顺带过企业微信那条通道）。去重靠 `qc_system_alert` 的 `(alert_key, alert_date)` 唯一索引：
**同一个等级一天只推一次**。为什么不只在状态变化时推——状态变成 `CRITICAL` 那天
正好没人上班，就永远错过了；问题没解决，第二天还是要顶在眼前。

---

## 二十二、返修工单

不良处置选了「返工」之后，坏件去了车间。在这之前，系统能回答的只有「这条不良
要返工」，回答不了「这几件现在在哪、谁在修、说好什么时候回来、回来几件好的」。
`qc_rework_order` 就是那张夹在坏件上的票。

```
OPEN 待返修 ──开工──→ DOING 返修中 ──完工──→ DONE 已完工
   └────────────── 取消 ──────────────→ CANCELLED 已取消
```

**为什么不加审批。** 返修是车间的活。加两级审批只会让票卡在系统里、件卡在返修区。

**为什么一条不良只许一张有效票。** 两张票意味着返修区两拨人各修一半，报数时
两边都说自己那半是全部，加起来比实际多。唯一索引 `uk_rework_defect`
（`WHERE status <> 'CANCELLED'`）在库层挡住；取消的票不占槽位，开错了可以重开。

### 1. 从不良开票

`POST /api/rework`

```json
{
  "defectId": 128,
  "qty": 5,
  "assignee": "三号返修台 老李",
  "dueDate": "2026-11-12",
  "requirement": "修螺纹，通止规过",
  "remark": ""
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `defectId` | 是 | 来源不良；处置必须是返工，或有待定（开票即视为选了返工） |
| `qty` | 否 | 送修**实际**件数（上限 200，与检验单同），不传取不良记录的件数。判出 5 件只送 3 件去修是常态 |
| `assignee` | 否 | 返修人 / 工位，自由文本——车间多数返修工没有系统账号 |
| `dueDate` | 否 | 要求完成日，默认三天后。传早于今天的日期会被拒（开出来就是超期） |
| `requirement` | 否 | 怎么修、修到什么程度 |

开票会连带把不良的处置状态置为 `PROCESSING`：实物已经在返修区了，系统里不能还写着「待处理」。

拒绝的情况：不良已处置完成 / 已让步接收 / 处置是报废或退货 / 原单未提交 /
已有有效票 / 送修数量不在 1~200（与检验单单次上限同值）。

### 2. 分页与列表

`GET /api/rework/page?current=1&size=10&status=DOING&keyword=RW2026&from=&to=&overdue=true`

`keyword` 同时匹配票号、不良名称、返修人、批次号。`overdue=true` 只看超期的。

返回的每条记录带 `overdue` / `overdueDays`，**由服务端算好下发**：手机时钟不准，
拿本地时间比会把「今天到期」判成超期。

`GET /api/rework/pending?limit=50` —— 未完工的票，超期排最前。手机待办用。
`GET /api/rework/by-inspection/{inspectionId}` —— 某张检验单下的票。
`GET /api/rework/{id}` —— 详情。

### 3. 指派 / 开工

`POST /api/rework/{id}/assign` `{ "assignee": "五号台 王师傅", "dueDate": "2026-11-15" }`

`dueDate` 不传就不改期限。返修人换了不换票——票跟着件走，不跟着人走。

`POST /api/rework/{id}/start` —— 待返修 → 返修中。重复调用无副作用。

### 4. 完工报数

`POST /api/rework/{id}/finish`

```json
{ "reworkedQty": 4, "scrappedQty": 1, "finishNote": "一件螺纹烂牙，无法修复" }
```

**返好 + 返废 必须正好等于送修数量。**

- 少报：有几件没交代去向（多半还在返修台上，或者被谁顺手扔了）
- 多报：多半是记错了票
- 返废 > 0 时 `finishNote` 必填

这条规则在三个地方各有一道：`ReworkBalance.checkFinish`（Java 纯逻辑，有单测）、
前端 `rework.js`（两端逐字一致）、库层 `ck_rework_finish`（CHECK，绕不过去）。
不是因为不信任谁，是因为账不齐的返修记录比没有记录更糟——它会让返工率、
返修报废率全部失真，而且没人会去查。

完工结果分两种情况：

| 返好件数 | 结果 |
|---|---|
| > 0 | 自动开一张复检单，**件数 = 返好件数**（不是送修件数），Ac=0 |
| = 0（全废） | **不开复检单**，不良直接改判 `SCRAP` 并置 `DONE` |

第二条是为了解开一个死锁：`DefectService.dispose` 要求「返工完成必须有 PASS 的复检单」，
而全废的件没有可检的东西，不良就永远停在处理中。实际业务里送去返修的件全废了，
处置结论本来就是报废。

复检件数跟着返好件数走同样重要：送 5 件、返好 4 件，复检单开 5 件会让第 5 个
件号永远没有实测值，而判定要求「每件每项都要有值」——那张单永远提交不了。

响应：

```json
{
  "order": { "...": "完工后的票据" },
  "recheckInspectionId": 501,
  "recheckInspectionNo": "QC202611090012"
}
```

全部返废时后两个字段为 `null`。

完工且生成了复检单，会给原单检验员推一条站内通知（`REWORK_DONE`）：
下一步是他的活。

### 5. 取消

`POST /api/rework/{id}/cancel` `{ "reason": "客户同意让步接收" }`

理由必填，**仅 ADMIN / REVIEWER 可调**：取消等于把这批件从账上抹掉。
取消后不良的处置状态退回 `PENDING`，处置方式保留不动——票取消了，
要不要改判由人重新定。

### 6. 与不良处置的互锁

有未完工的票（`OPEN` / `DOING`）时，`POST /api/defects/{id}/dispose` 改成
报废/退货/让步会被拒，并指出票号。否则返修区还在按票修，系统里已经判了报废，
票成了孤儿，报回来的件是「已经报废」的件。

### 7. 待办

`GET /api/todo/summary` 新增两个数字：

- `reworkOpen`：在返修区的票数（**不进** `actionable`，那是返修工的活）
- `reworkOverdue`：超过要求完成日还没完工的（**进** `actionable`——说好三天回来、
  第五天还没回来，得有人去问一句）

### 8. 不良列表上的票号

`GET /api/defects` 的每条记录多两个字段：`reworkNo`、`reworkStatus`。
不良列表最常被问的第二句话就是「这件送去修了没有」，只给状态不给票号，
人还得再去返修页搜一次。

---

## 二十三、量具测量系统分析（GR&R，V22）

### 1. 这一节在讲一件反常识的事

量具台账管的是「校准没过期」，那是**时间维度**。校准合格只说明这把尺和标准器对得上，
**不说明它能分辨出这道公差上的差异**。一把分度值 0.05 的卡尺去测 10.00±0.02 的外径，
校准证书照样合格，测出来的数据照样不能用。

GR&R（Gauge Repeatability & Reproducibility）研究的就是这件事：找几个人、拿几件零件、
每人每件反复测几遍，把读数的波动拆成三份——

| 来源 | 名字 | 谁的问题 |
|---|---|---|
| 同一个人测同一件，反复几次的差 | **EV 重复性** | 量具分辨力、装夹方式 |
| 不同的人测同一件，彼此之间的差 | **AV 再现性** | 测量手法、定位基准、读数取点 |
| 零件之间本身就不一样 | **PV 零件变差** | 这才是我们想看到的差异 |

`%GRR = 100 × GRR / TV`，`TV = √(GRR² + PV²)`。判档：

| %GRR | 结论 | 下一步 |
|---|---|---|
| ≤ 10% | 可接受 | 这套测量系统能撑住判合格与不合格 |
| 10% ~ 30% | 有条件接受 | 能用，但卡在公差边上的件判起来会犹豫 |
| > 30% | 不可接受 | 读数的波动已经能和零件差异相比，判合格基本靠运气 |

顺带算 `ndc = 1.41 × PV / GRR`（可区分类别数，AIAG 要求 ≥ 5）。
%GRR 判档用的是**定标到 2 位小数之后的数**，和页面上显示的是同一个值——
否则会出现「页面上写着 10.00%，结论却是有条件接受」这种自相矛盾的画面。

### 2. 录入

```
POST /api/msa
{
  "gaugeId": 7,
  "productId": 1,              // 可不填
  "itemId": 3,                 // 可不填
  "characteristic": "外径",
  "unit": "mm",
  "lsl": 9.98, "usl": 10.02,   // 可不填；都填了才会算「按公差的 %GRR」
  "appraisers": ["张三", "李四", "王五"],
  "readings": [ [ [10.01,10.02,10.01], ...10 件... ], ...3 人... ],
  "remark": "含 2 件接近上限的"
}
```

**评价人数 / 零件数 / 重复次数不要报**：从 `readings` 的三个维度推出来。报了反而会出现
「说 3 人、只给 2 组读数」这种一路写进库的不一致。

范围（与系数表对齐，超出算不出 %GRR）：

| 维度 | 范围 | 对应系数 |
|---|---|---|
| 评价人数 a | 2 ~ 5 | K2 |
| 零件数 b | 2 ~ 10 | K3 |
| 每件重复次数 n | 2 ~ 3 | K1 |

校验依次是：结构范围 → 逐格漏填/脏值 → 量具可不可用（复用 `GaugeService.assertUsable`：
停用、报废、未校准、**超期**一律拒）→ 上下限是否填反。评价人**不许重名**——
真出了再现性问题，第一个要问的就是「谁跟谁对不上」，重名就问不出来。

单号 `MSA + yyyyMMdd + 3 位`，走 `DocNoService.next()`。

### 3. 查询

```
GET /api/msa/page?current=1&size=10&keyword=卡尺&gaugeId=7&verdict=BAD
GET /api/msa/{id}
```

`keyword` 匹配研究号 / 量具编号 / 量具名称 / 特性。

`verdict` 有三档（`GOOD` / `MARGINAL` / `BAD`）。**它不是数据库里的一列，是算出来的**，
所以带它的时候服务端先取全量、过滤完再在内存里分页；反过来「先分页再过滤」会出现
「共 5 条，这页只显示 1 条」这种越看越可疑的界面。研究记录一年也就几十条，这点开销值得。

列表**不带** `readings`（一页 10 条 × 90 个读数，列表一个也用不上），但一定带 `result`。
明细带读数。

### 4. 返回结构

```json
{
  "studyNo": "MSA20261109001",
  "appraiserCount": 3, "partCount": 10, "trialCount": 3,
  "result": {
    "ev": 0.1772, "av": 0.1963, "grr": 0.2645, "pv": 0.5231, "tv": 0.5862,
    "pctEv": 30.24, "pctAv": 33.49, "pctGrr": 45.12, "pctPv": 89.24,
    "pctGrrTol": 5.75,
    "ndc": 2,
    "verdict": "BAD",
    "verdictText": "%GRR 45.12%，不可接受：读数的波动已经能和零件本身的差异相比……",
    "notes": ["零件只有 3 件（AIAG 建议 10 件）……", "重复性与再现性各占一半……"],
    "appraiserMean": [10.6, 10.8, 11.0],
    "appraiserRange": [0.2, 0.2, 0.2],
    "partMean": [10.3, 10.8, 11.3],
    "partRange": [0.4, 0.4, 0.4]
  }
}
```

`notes` 是这一节的重点，每条都指向一个具体动作：

| 触发条件 | 说的是什么 |
|---|---|
| 零件数 < 5 | PV 估不准，%GRR 只能当参考 |
| 零件数 < 10 | 取样要覆盖公差带，全挑刚下线的同一批会把 %GRR 算得虚高 |
| AV 根号内为负 | 再现性算出来是负数，按 0 处理（手册认可的算法行为，不是 bug） |
| ndc < 5 | 只能分出 N 个层级，分不出足够细的档 |
| 某格 n 次读数一字不差 | 很可能是照着第一遍抄的；照抄会把 %GRR 算小 |
| 两个口径判档不一致 | 按过程变差 X%、按公差 Y%，评审时选一个，别挑好看的 |
| %EV ≥ %AV × 1.5 | 重复性占大头：先查分辨力和装夹，**换人不解决** |
| %AV ≥ %EV × 1.5 | 再现性占大头：先统一手法和基准，**换量具不解决** |

最后两条是整个功能的落点。只给一个「45% 不可接受」的话，人知道不行但不知道从哪下手，
最常见的反应是去买一把更贵的量具——如果问题在手法，新量具一样不合格。

### 5. 作废

```
POST /api/msa/7/cancel   { "reason": "读数录错了一列，重做" }
```

研究记录**不可改**：读数一旦是编的，改一个数字就能把它变成「合格的」，这条记录就失去了
作为证据的价值。录错只能作废重做（逻辑删，原始记录留在库里），理由必填；
管理员或录入人本人才能作废。

### 6. 与其他模块的关系

- **量具台账**：研究选用的量具必须当前可用。反过来说，台账上「这把尺做过几次 GRR、
  上次什么结论」现在有了答案（`GET /api/msa/page?gaugeId=7`）
- **过程能力**：SPC 判异里 R7（15 点挤在 1σ 内）的提示是「先查量具分辨力」，
  这句话指向的就是这里。**顺序不能反**——GRR 不合格时，控制图和 Cpk 算得再漂亮也是噪声分析
- **录入页实时预览**：`qc-pc/src/utils/grr.js` 是同一套算法的前端副本，边录边算，
  `test/grr.test.mjs` 与 `GrrStudyTest` 锁在同一组样例数据上，两边的数字必须一致。
  前端**只算数不生成文案**——那些「先查分辨力还是先统一手法」的话只在服务端一处，
  否则同一句话两份副本，改了一处忘了另一处，页面上就会出现两种说法

---

## 二十四、纠正预防措施（CAPA，V23）

### 1. 为什么要有这一节

到这里系统能答的已经不少：帕累托说哪类不良最多，SPC 说过程稳不稳，GR&R 说量出来的数
信不信，返修票说那批坏件现在在哪。四件事都答得清清楚楚。

唯独答不上一句：**同一个毛病，为什么修了三次还在犯。**

帕累托上排第一的那根柱子，三个月里在五个批次上各出现一次。报表上它是一根柱子，
车间里它是五件互不相干的麻烦事——每次都是「这一批返工掉」，没有人回头看它为什么反复出现。
闭环只闭到了「把坏件处理掉」，没闭到「让它不再出现」。

这张表补那半截。

### 2. 状态机

```
OPEN 待分析 ──start──→ DOING 措施执行中 ──submit-verify──→ VERIFYING 待验证
                            ↑                                    │
                            └────── verify(无效) ────────────────┤
                                                                 └─ verify(有效) → CLOSED
任意未关闭态 ──cancel(理由)──→ CANCELLED
```

不做审批流，理由和返修票一样：措施有没有用不是批出来的，是**验**出来的。
加两级审批只会让一张单在系统里走三周，而问题还在产线上照原样发生。

### 3. 建单

```
POST /api/capa
{
  "title": "外径连续 3 批超上限",
  "problem": "B1/B2/B3 三批各 1 件超上限 0.03",
  "productId": 1,                 // 可不填；不填则再犯检测失效
  "defectCode": "OD-001",         // 可不填
  "severity": "MAJOR",            // CRITICAL / MAJOR / MINOR，默认 MINOR
  "owner": "张三",                 // 必填
  "dueDate": "2026-11-25",        // 可不填，页面默认两周
  "sourceType": "DEFECT",         // DEFECT / COMPLAINT / AUDIT / OTHER
  "sourceId": 12
}
```

必填只有三样：标题、问题描述、责任人。**建单不要求根因和措施**——查原因要时间，
建单这一下就逼着人编一个，只会编出「员工质量意识不足」这种正确而无用的话。

`sourceType = DEFECT` 且给了 `sourceId` 时，**产品与不良标识以来源不良为准**，服务端覆盖前端传的值：
手工填的那两个一旦和来源对不上，再犯检测就永远匹配不到自己，于是「同一个毛病反复犯」
在系统里彻底看不见。

`defectCode` 存的是「不良标识」：**有代码存代码，没有代码存名称**。现场不少临时性缺陷
没建代码，按 code 匹配会把它们全漏掉，而漏掉的恰好是最容易反复的那一类。
查询侧对应 `coalesce(defect_code, defect_name)`，两边同口径。

单号 `CAPA + yyyyMMdd + 3 位`，走 `DocNoService.next()`。
`(source_type, source_id)` 上有部分唯一索引（`status <> 'CANCELLED'`）——同一件事开两张措施，
两边会各改各的，最后谁也说不清做完了没有；撤销的不占槽位，可以重开。

### 4. 填根因与措施

```
POST /api/capa/7/analysis
{ "rootCause": "...", "actionPlan": "...", "preventPlan": "..." }
```

只有 `OPEN` / `DOING` 能改。进了 `VERIFYING` 就冻结——验证人看到的东西必须和提交时一致，
边验边改等于追着一个移动的靶子。真要补，让验证人判无效退回来。

**没写根因不许提交验证**（`ck_capa_verifying` 与 `CapaRules.checkSubmitVerify` 两处同口径）。
这是整条链上最要紧的一道门：现场最常见的偷懒是「把它返工掉就完了，原因回头再说」，
回头没有那一天，下个月同一个不良代码又出现在帕累托第一行。**没有根因的措施只是处理，不是纠正。**

`preventPlan` 空着不拦关闭（很多时候确实只有这一处），但关闭时会往 `remark` 里补一句提醒：
这台机器调好了、另外三台同型号的照样会犯。

### 5. 验证：为什么「自己不能验自己」

```
POST /api/capa/7/verify   { "effective": true, "note": "连续 5 批未再出现，Cpk 从 0.9 回到 1.4" }
```

| 判定 | 结果 |
|---|---|
| `effective: true` | `CLOSED`，记 `closedBy / closedAt` |
| `effective: false` | 退回 `DOING`，`closeEffective` 留 `FALSE` 作为「上一次没过」的痕迹 |

两条硬规矩：

1. **责任人不能验自己**。谁都觉得自己的措施有用；一旦可以自验，表上全是「已验证有效」，
   而问题照旧在产线上发生。接口层要 `ADMIN` / `REVIEWER`，服务层再拦一次 `owner == 操作人`——
   两道看着重复，挡的不是同一件事：前者管「谁有资格验」，后者管「这一步不能自己盖章」，
   绕过接口直接发请求也过不了第二道
2. **判「无效」是正常路径，不是失败**。措施不够就退回去改，比硬着头皮关掉、
   下个月再犯一遍便宜得多。所以刻意不做「验证通过率」这种指标——一旦验证成了 KPI，
   就没有人会判无效，CAPA 系统退化成一张安慰表

`verifyNote` 必填。验证结论写着「已改善」的记录，三个月后没人知道当时看了什么数据。

### 6. 再犯检测

```
GET /api/capa/recurrence?productId=1&defectKey=OD-001
```

```json
{
  "occ": 5, "qty": 7, "batches": 3, "lastAt": "2026-11-09T10:12:00Z",
  "closedCapas": [{ "capaNo": "CAPA20260801003", "closedAt": "...", "effective": true,
                    "verifyNote": "连续 5 批未再出现" }],
  "advice": "这条不良以前关过措施、还判过「验证有效」，现在又出现——说明当时打偏了根因。……"
}
```

`occ` 与 `batches` 要分开看：同一批里出现五次，和五批各出现一次，是完全不同的两件事——
前者是这一批的问题，后者是过程的病。

`advice` 的四档（顺序即优先级）：

| 条件 | 说什么 |
|---|---|
| 有已关闭且判过有效 | 上次打偏了根因，这次别再照那套做一遍 |
| `occ ≥ 3` 且 `batches ≥ 2` | 跨批次反复，不是偶发，值得查根因 |
| `occ ≥ 3` 但都挤在一批 | 更像这批的来料或设备问题，先查批次，别急着上措施 |
| 其它 | 偶发，可以先只做纠正，攒够次数再上措施 |

回看窗口 90 天（`CapaService.HISTORY_DAYS`）。太短看不见「改完慢慢回来」，
太长会把早就不存在的问题算成「反复出现」，反而稀释掉真正在犯的那一条。

页面上有两个落点：
- **不良页的帕累托每一行有「立措施」**，带着不良标识和现成的问题描述跳过来。
  看到柱子的那一刻就该能立措施，两个页面之间少一次「回头再想想」，那些想法通常就不会再有了
- **新建措施时选完产品与不良**，下方实时显示「以前犯过几次」。这一段比建完之后再想
  「好像以前也修过」有用得多

### 7. 待办口径

| 字段 | 含义 | 进 actionable |
|---|---|---|
| `capaOpen` | 还开着的总数 | 否——那是责任人的活，进度他自己看 |
| `capaOverdue` | 超期未完成 | **是**——说好两周改完、一个月了还没动，等于这件事被忘了 |
| `capaVerifying` | 等着人判有没有用 | 否——单列出来，那是审核员的活，不是责任人在拖 |

与返修票同一套取舍：`reworkOpen` 不进（返修工的活），`reworkOverdue` 进（说好回来没回来是事故）。

### 8. 权限

| 操作 | 要求 |
|---|---|
| 建、开始、填措施、提交验证 | 责任人本人，或 `ADMIN` / `REVIEWER` |
| 验证、撤销 | 只能 `ADMIN` / `REVIEWER`；验证还额外拦「责任人本人」 |
| 查询 | 所有登录用户 |

给管理员例外是有意的：厂里可能就一个人管质量，不给他例外的结果不是「更规范」，
而是「这条措施永远没人动」。

### 9. 与其他模块的关系

- **帕累托**：柱子指出问题，这里接住动作。这是「分析 → 动作」之间原本断开的那一节
- **返修工单**：返修票回答「这批坏件怎么办」，CAPA 回答「怎么让它不再坏」。
  两者可能同时挂在同一条不良上，各自的生命周期互不干扰
- **客诉**：`sourceType = COMPLAINT`。客户投诉往往就是「同一个毛病第 N 次出现」，
  客诉模块的闭环解决的是「给客户答复」，CAPA 解决的是「不让它再发生」
- **不需要新增数据库脚本之外的东西**：数据全在 `qc_capa` 一张表上，没有冗余统计表

### 10. 移动端入口（`qc-mobile/pages/capa/capa.vue`）

责任人在车间，改措施不该要求他回电脑。手机上的界面对应「我负责的」与「待验证」两页签：

- 「我负责的」= `GET /api/capa/page?owner=<displayName>`，三个动作按钮按状态出现：
  待分析 → 开始处理；待分析/措施执行中 → 填原因；措施执行中 → 提交验证
- 「待验证」= `GET /api/capa/page?status=VERIFYING`，只对 `ADMIN` / `REVIEWER` 显示；
  自己负责的那条从这一页里排除（它已经在「我负责的」里），免得同一件事看到两遍
- **手机上不写判定文案**：`common/capa.js` 只有 `canStart/canEdit/canSubmit/missingForSubmit/dueLabel`
  这类布尔与字段名，为什么被拒、下一步做什么仍然只有服务端 `CapaRules` 一处。
  两端各写一套话，早晚变成「手机上说得通、服务端说不行」，而这恰恰是给人看的部分
- 「超期」一律用服务端算好的 `overdue / overdueDays`（它判的是「还开着的才算超期」）；
  手机只算「还剩几天」，且自己拆 `YYYY-MM-DD` 再走 `Date.UTC`——
  iOS Safari 上 `new Date('2026-11-09 10:00:00')` 是 `Invalid Date`
- 撤销不在手机上做：那是质量条线的判断，且必须写理由
