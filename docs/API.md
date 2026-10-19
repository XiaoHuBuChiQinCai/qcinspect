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
| GET | `/standards?productId=1` | 某产品的所有版本（含已作废） |
| GET | `/standards/{id}/items` | 标准包含的检验项 |
| POST | `/standards` | 新建草稿标准（version 自动为 1） |
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

## 五、状态值约定

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
| `qc_item.value_type` | `NUMBER` / `ENUM` / `BOOL` / `TEXT` |
| `qc_standard_item.value_mode` | `SINGLE` 单值 / `MULTIPLE` 多点 |
| `qc_batch.inspect_type` | `IQC` 来料 / `IPQC` 过程 / `FQC` 成品 / `OQC` 出货 |
