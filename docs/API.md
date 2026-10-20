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
| POST | `/products` | 新增 / 修改产品 | body 见下 |
| GET | `/items` | 检验项列表 | `groupName` 可选 |
| POST | `/items` | 新增 / 修改检验项 | body 见下 |
| GET | `/batches` | 批次列表 | `status` / `productId` 可选 |
| POST | `/batches` | 新增 / 修改批次 | body 见下 |

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

**标准**
```json
{
  "standardCode": "S-FLANGE-01",
  "productId": 1,
  "samplingType": "SAMPLE",
  "sampleSize": 5,
  "allowedNgCount": 1,
  "effectiveFrom": "2026-10-19",
  "remark": "法兰盘成品检验标准"
}
```

> `allowedNgCount` 是**允收数 Ac**：本批允许的不合格**件数**，不是不合格项数。
> 抽 5 件、Ac=1，意味着最多容忍 1 件不合格。

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

---

## 四、统计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/inspections/stat/daily` | 近 30 天检验量、单级与件级合格数 |
| GET | `/inspections/stat/defect` | 缺陷分布（帕累托数据源） |

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
| GET | `/gauges/expiring?days=30` | 已过期 + N 天内到期 |
| POST | `/gauges` | 新增 / 修改（`nextCalibrateDate` 由后端算，不用传） |
| POST | `/gauges/{id}/calibrate?calibrateDate=2026-10-19&cycleDays=365` | 送检后登记新校准日 |
| POST | `/gauges/{id}/scrap?reason=跌落损坏` | 报废 |

**提交检验单时**，每个 `line` 可带 `gaugeId`。带了就会被校验：

- 已报废 → `选用的量具 G-00x 已报废，不能用于检验`
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
| POST | `/defects/{id}/recheck` | 挂返工后的复检单 |
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

- 返工单 → `DONE` 时**必须已有 `recheckInspectionId`**，否则报「返工完成后必须挂一张复检单」
- `disposition = CONCESSION` 走 `dispose` 会被拒，必须走 `/concession` 并带理由和授权人（DB 层还有 CHECK 约束兜底）

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
| GET | `/shipments?batchId=1` | 出货记录 |
| POST | `/shipments` | 新增出货 |

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

手机端首页角标和工作台都从这里取数。五个数字一次给全，避免前端为角标发五次请求。

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/todo/summary` | 见下 |
| GET | `/todo/reviews` | 待审核的检验单（`SUBMITTED`），已补 `batchNo` / `productName` |
| GET | `/todo/defects` | 待处置不良（`PENDING` + `PROCESSING`），带 `inspectionNo` |
| GET | `/todo/mine` | `{ drafts: [...], rejected: [...] }`，按登录人过滤 |
| GET | `/todo/gauges` | 超期未校准量具，带 `overdueDays` |

`/todo/summary`：

```json
{
  "pendingReview": 3,
  "pendingDefect": 5,
  "overdueGauge": 1,
  "myDraft": 2,
  "myRejected": 1,
  "actionable": 9
}
```

口径说明：

- **待审核**只算 `SUBMITTED`。草稿是检验员自己的活，不该出现在审核员待办里
- **被退回**的判定是「当前是草稿 + 历史上出现过 `REJECT`」。改完重新提交后状态变 `SUBMITTED`，自然从待办消失，不需要已读标记
- **`actionable`** = 待审核 + 待处置 + 我被退回，**不含**量具超期：量具不是单据，混进去会让人误以为积压了几十张单
- 量具超期单独立一项。超期量具量出来的数据，体系审核直接判无效，它比「有几张单待审」更要紧
