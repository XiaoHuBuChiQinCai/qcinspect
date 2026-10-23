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
| POST | `/auth/users/{id}/reset-password` | 重置他人密码 | ADMIN |

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
| GET | `/batches` | 批次列表 | `status` / `productId` / `keyword` 可选 |
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
| POST | `/standards/{id}/publish` | 以该版本为模板发布新版本（自动生效） |
| POST | `/standards/{id}/activate` | 启用草稿版本 |

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
  "locked": false
}
```

- `pieces[]` 是主结构，前端做件切换用
- `groups` 是全部行按组的平铺（兼容旧前端，多件时会重复）
- `locked = true` 表示已提交或已归档，前端应置为只读

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
    { "lineId": 1, "pieceNo": 1, "itemName": "外径", "pass": true, "reason": null },
    { "lineId": 9, "pieceNo": 3, "itemName": "外径", "pass": false, "reason": "第 1 点 12.3 > 12.05" }
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
  "pass": true,
  "reason": "不合格 1 件（第 3 件），未超过允收数 1 件。关键项问题：第 3 件 关键项不合格：外径（第 1 点 12.3 > 12.05）"
}
```

**判定规则**：
1. 件：同一 `pieceNo` 任一行 NG → 该件 NG
2. 批：`failedQty <= acceptQty` → 批合格
3. 没有任何检验数据 → 判不合格（空单不能判合格）
4. 关键项 NG 落在允收范围内时结论仍为合格，但 `reason` 必须带关键项提示

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
| POST | `/gauges/{id}/scrap?reason=跌落损坏` | 报废 |

**状态**：`ACTIVE` 可用 / `SUSPENDED` 校准不合格停用 / `SCRAPPED` 已报废。

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

`SUSPENDED` 的量具修好后重新登记校准，结果 `PASS` 即自动恢复 `ACTIVE`。
`POST /gauges` 建档、以及编辑时改「上次校准」日期，都会自动补一条履历
（备注分别注明「建档时」「编辑时修改」），保证主表与履历不脱账。

### 提交检验单时的校验

每个 `line` 可带 `gaugeId`，带了就会校验，四种情况都拦：

- 已报废 → `选用的量具 G-00x 已报废，不能用于检验`
- 已停用 → `上次校准不合格，已停用。维修并重新校准合格后才能再用`
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
| GET | `/images/{id}/raw` | 取图片二进制（本地存储模式） | **公开** |
| DELETE | `/images/{id}` | 删除照片 | 登录 |

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
  "url": "/api/images/12/raw",
  "createdBy": "系统管理员",
  "createdAt": "2026-10-19T17:30:12"
}
```

> `url` 两种形态：`local` 存储给 `/api/images/{id}/raw`（本服务代理），`minio` 存储给 7 天有效的预签名地址。
> 只接受 jpg / jpeg / png / webp / bmp，单张 ≤ 10MB。`Content-Type` 按扩展名反推，不采信浏览器上报值。
> `/raw` 放行是因为 `<img src>` 带不了 Authorization 头——**公网部署时必须收紧这里**。

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

