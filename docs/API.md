# 接口清单

Base URL：`http://localhost:8080/api`
统一返回：`{ "code": 0, "msg": "ok", "data": ... }`，`code != 0` 表示失败。

> `operator` 是操作人标识，目前通过 query 参数传（`?operator=张三`），缺省 `system`。
> 接上 Spring Security 之后应改为从 token 取，届时只需改 Controller 一层。

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

副作用：明细写回 `result` / `judgeReason`，生成 `qc_defect` 不良记录（带件号），单据转 `SUBMITTED`，批次转 `DONE`，写一条 `SUBMIT` 日志。

**提交后锁定**。重复提交 `DRAFT` 以外的状态会返回错误，必须先 `reopen`。

### 5. 解锁 / 归档 / 作废

| 方法 | 路径 | 前置状态 | 说明 |
|---|---|---|---|
| POST | `/inspections/{id}/reopen?reason=xxx` | `SUBMITTED` | 回 `DRAFT`，`reason` 必填 |
| POST | `/inspections/{id}/close` | `SUBMITTED` | 转 `CLOSED` 归档 |
| POST | `/inspections/{id}/void?reason=xxx` | `DRAFT` / `SUBMITTED` | 转 `VOID`，`reason` 必填 |

三个动作都写日志。`reason` 为空直接报错——解锁和作废必须说清楚为什么，这是质量记录可追溯的前提。

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
  }
]
```

### 7. 分页

`GET /inspections/page?current=1&size=10&status=SUBMITTED&batchId=1`

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

## 八、状态值约定

| 字段 | 取值 |
|---|---|
| `qc_standard.status` | `DRAFT` 草稿 / `ACTIVE` 生效 / `OBSOLETE` 作废 |
| `qc_standard.sampling_type` | `FULL` 全检 / `SAMPLE` 抽检 / `NONE` 免检 |
| `qc_batch.status` | `PENDING` 待检 / `INSPECTING` 检验中 / `DONE` 已完成 |
| `qc_inspection.status` | `DRAFT` 草稿 / `SUBMITTED` 已提交（锁定）/ `CLOSED` 已归档 / `VOID` 已作废 |
| `qc_inspection.result` | `PASS` / `FAIL` / `NA` |
| `qc_inspection_line.result` | `PASS` / `FAIL` / `PENDING` |
| `qc_inspection_log.action` | `CREATE` 建单 / `SUBMIT` 提交 / `REOPEN` 解锁 / `CLOSE` 归档 / `VOID` 作废 |
| `qc_defect.disposition` | `REWORK` 返工 / `SCRAP` 报废 / `CONCESSION` 让步接收 / `RETURN` 退货 |
| `qc_defect.disposition_status` | `PENDING` 待处理 / `PROCESSING` 处理中 / `DONE` 已完成 / `WAIVED` 已让步 |
| `qc_gauge.status` | `ACTIVE` 可用 / `SCRAPPED` 已报废 |
| `qc_item.value_type` | `NUMBER` / `ENUM` / `BOOL` / `TEXT` |
| `qc_standard_item.value_mode` | `SINGLE` 单值 / `MULTIPLE` 多点 |
| `qc_batch.inspect_type` | `IQC` 来料 / `IPQC` 过程 / `FQC` 成品 / `OQC` 出货 |
