<template>
  <div class="report-page">
    <div class="toolbar no-print">
      <el-button @click="$router.back()">返回</el-button>
      <div class="tip" v-if="data && !data.conclusion.final">
        该单据尚未提交，判定结论未生效，仅供参考
      </div>
      <div class="spacer" />
      <el-button type="primary" :disabled="!data" @click="print">打印 / 另存 PDF</el-button>
    </div>

    <div v-if="loading" class="loading">加载中…</div>

    <div v-else-if="data" class="paper">
      <div class="head">
        <div class="company">{{ companyName }}</div>
        <h1 class="title">工件检验报告</h1>
        <div class="meta-line">
          <span>报告编号：{{ data.inspection.inspectionNo }}</span>
          <span>打印时间：{{ fmt(data.printedAt) }}</span>
        </div>
      </div>

      <table class="head-table">
        <tbody>
          <tr>
            <th>产品名称</th>
            <td>{{ data.product?.productName || '-' }}</td>
            <th>产品编号</th>
            <td>{{ data.product?.productCode || '-' }}</td>
          </tr>
          <tr>
            <th>规格型号</th>
            <td>{{ data.product?.spec || '-' }}</td>
            <th>批次号</th>
            <td>{{ data.batch?.batchNo || '-' }}</td>
          </tr>
          <tr>
            <th>工单号</th>
            <td>{{ data.batch?.workOrderNo || '-' }}</td>
            <th>来料/供应商</th>
            <td>{{ data.batch?.supplier || '-' }}</td>
          </tr>
          <tr>
            <th>检验类型</th>
            <td>{{ inspectTypeText }}</td>
            <th>标准版本</th>
            <td>V{{ data.inspection.standardVersion || '-' }}</td>
          </tr>
          <tr>
            <th>批量 / 抽检</th>
            <td>{{ data.batch?.qty ?? '-' }} / {{ data.conclusion.sampleQty ?? '-' }}</td>
            <th>检验员</th>
            <td>{{ data.inspection.inspectorName || '-' }}</td>
          </tr>
          <tr>
            <th>检验日期</th>
            <td>{{ fmt(data.inspection.inspectedAt || data.inspection.createdAt) }}</td>
            <th>检验单状态</th>
            <td>{{ statusText }}</td>
          </tr>
        </tbody>
      </table>

      <div class="verdict" :class="data.conclusion.pass ? 'ok' : 'ng'">
        <div class="verdict-label">判定结论</div>
        <div class="verdict-value">{{ verdictText }}</div>
        <div class="verdict-reason">{{ data.conclusion.reason || '—' }}</div>
      </div>

      <table class="count-table">
        <thead>
          <tr>
            <th>抽检件数</th>
            <th>合格件数</th>
            <th>不合格件数</th>
            <th>允收数 Ac</th>
            <th>NG 项次</th>
            <th>关键项 NG</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{{ data.conclusion.sampleQty ?? '-' }}</td>
            <td>{{ data.conclusion.passedQty ?? '-' }}</td>
            <td>{{ data.conclusion.failedQty ?? '-' }}</td>
            <td>{{ data.conclusion.acceptQty ?? '-' }}</td>
            <td>{{ data.conclusion.ngCount ?? 0 }}</td>
            <td :class="{ danger: (data.conclusion.criticalNgCount || 0) > 0 }">
              {{ data.conclusion.criticalNgCount ?? 0 }}
            </td>
          </tr>
        </tbody>
      </table>

      <div class="sec-title">检验明细</div>
      <table class="detail-table">
        <thead>
          <tr>
            <th style="width: 48px">件号</th>
            <th style="width: 70px">分组</th>
            <th>检验项</th>
            <th>标准要求</th>
            <th>实测值</th>
            <th style="width: 54px">判定</th>
            <th style="width: 90px">量具</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="p in data.pieces" :key="p.pieceNo">
            <tr v-for="(row, idx) in p.rows" :key="row.id">
              <td class="center">{{ idx === 0 ? '#' + p.pieceNo : '' }}</td>
              <td class="center">{{ row.groupName || '-' }}</td>
              <td>
                {{ row.itemName }}
                <span v-if="row.isCritical" class="critical-mark">关键</span>
              </td>
              <td>{{ specText(row) }}</td>
              <td :class="{ 'ng-text': row.result === 'FAIL' }">{{ measuredText(row) }}</td>
              <td class="center">
                <span :class="['judge', row.result === 'FAIL' ? 'judge-ng' : 'judge-ok']">
                  {{ row.result === 'FAIL' ? 'NG' : row.result === 'PASS' ? 'OK' : '—' }}
                </span>
              </td>
              <td class="center">{{ row.gaugeCode || '-' }}</td>
            </tr>
          </template>
          <tr v-if="!data.pieces.length">
            <td colspan="7" class="center empty">暂无检验明细</td>
          </tr>
        </tbody>
      </table>

      <template v-if="data.defects && data.defects.length">
        <div class="sec-title">不良记录与处置</div>
        <table class="detail-table">
          <thead>
            <tr>
              <th style="width: 48px">件号</th>
              <th style="width: 100px">不良代码</th>
              <th>不良描述</th>
              <th style="width: 90px">处置方式</th>
              <th style="width: 90px">状态</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in data.defects" :key="d.id">
              <td class="center">#{{ d.pieceNo || 1 }}</td>
              <td class="center">{{ d.defectCode || '-' }}</td>
              <td>{{ d.defectName || d.description || '-' }}</td>
              <td class="center">{{ dispositionText(d.disposition) }}</td>
              <td class="center">{{ d.status || '-' }}</td>
              <td>{{ d.dispositionNote || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </template>

      <template v-if="data.images && data.images.length">
        <div class="sec-title">现场照片</div>
        <div class="photos">
          <div v-for="img in data.images" :key="img.id" class="photo">
            <img :src="img.url" :alt="img.fileName" />
            <div class="photo-cap">{{ img.fileName }}</div>
          </div>
        </div>
      </template>

      <div class="sign-row">
        <div class="sign">检验员：{{ data.inspection.inspectorName || '' }}<span class="sign-line" /></div>
        <div class="sign">审核：<span class="sign-line" /></div>
        <div class="sign">批准：<span class="sign-line" /></div>
        <div class="sign">日期：{{ fmt(data.printedAt).slice(0, 10) }}</div>
      </div>

      <div class="foot">
        本报告由 {{ companyName }} 质检系统生成，检验数据于提交时冻结，不随后续标准改版变动。
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'

const route = useRoute()
const loading = ref(true)
const data = ref(null)
const companyName = ref('工件质检中心')

const inspectTypeText = computed(() => {
  const map = { IQC: '来料检验 IQC', IPQC: '过程检验 IPQC', FQC: '成品检验 FQC', OQC: '出货检验 OQC' }
  return map[data.value?.inspection?.inspectType] || data.value?.inspection?.inspectType || '-'
})

const statusText = computed(() => {
  const map = { DRAFT: '草稿', SUBMITTED: '已提交', CLOSED: '已归档', VOID: '已作废' }
  return map[data.value?.inspection?.status] || data.value?.inspection?.status || '-'
})

const verdictText = computed(() => {
  if (!data.value) return '-'
  if (!data.value.conclusion.final) return '待判定'
  return data.value.conclusion.pass ? '合 格' : '不合格'
})

// 后端 LocalDateTime 序列化成 ISO 格式（带 T），报告上换成可读写法
function fmt(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

function parseOptions(json) {
  if (!json) return []
  try {
    const arr = typeof json === 'string' ? JSON.parse(json) : json
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

// 把快照里的限值还原成工程师看得懂的写法
function specText(row) {
  const unit = row.unit || ''
  if (row.valueType === 'NUMBER') {
    const min = row.minValue
    const max = row.maxValue
    const multi = row.valueMode === 'MULTIPLE' && row.pointCount > 1 ? `（${row.pointCount} 点均需满足）` : ''
    if (min != null && max != null) return `${min} ~ ${max}${unit}${multi}`
    if (min != null) return `≥ ${min}${unit}${multi}`
    if (max != null) return `≤ ${max}${unit}${multi}`
    return `数值${multi}`
  }
  if (row.valueType === 'ENUM') {
    const pass = parseOptions(row.optionsJson).filter(o => o.pass).map(o => o.name)
    return pass.length ? `合格项：${pass.join(' / ')}` : '枚举（未配置合格项）'
  }
  if (row.valueType === 'BOOL') {
    // bool_pass_value 的语义是「录入该值时判合格」
    return row.boolPassValue === false ? '应无（有则 NG）' : '应有（无则 NG）'
  }
  return '定性记录，不参与判定'
}

function measuredText(row) {
  if (row.valueType === 'TEXT') return row.measuredText || '—'
  if (row.valueType === 'ENUM') {
    const opts = parseOptions(row.optionsJson)
    const values = parseArr(row.measuredValues)
    return values.map(v => opts.find(o => o.code === v)?.name || v).join(' / ') || '—'
  }
  if (row.valueType === 'BOOL') {
    const v = parseArr(row.measuredValues)[0]
    if (v === undefined) return '—'
    return String(v).toLowerCase() === 'true' || v === '有' ? '有' : '无'
  }
  const vals = parseArr(row.measuredValues)
  if (!vals.length) return '—'
  return vals.join(' / ') + (row.unit || '')
}

function parseArr(json) {  if (!json) return []
  try {
    const arr = typeof json === 'string' ? JSON.parse(json) : json
    return Array.isArray(arr) ? arr : [String(arr)]
  } catch {
    return [String(json)]
  }
}

function dispositionText(d) {
  const map = { REWORK: '返工', SCRAP: '报废', CONCESSION: '让步接收', RETURN: '退货' }
  return map[d] || d || '-'
}

function print() {
  window.print()
}

onMounted(async () => {
  try {
    data.value = await http.get(`/reports/inspection/${route.params.id}`)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.report-page { background: #f0f2f5; min-height: 100vh; padding: 0 0 40px; }
.toolbar {
  position: sticky; top: 0; z-index: 10;
  display: flex; align-items: center; gap: 12px;
  padding: 12px 24px; background: #fff; border-bottom: 1px solid #e4e7ed;
}
.toolbar .spacer { flex: 1; }
.toolbar .tip { color: #e6a23c; font-size: 13px; }
.loading { padding: 60px; text-align: center; color: #909399; }

.paper {
  width: 210mm; min-height: 297mm; margin: 20px auto; padding: 14mm 14mm 12mm;
  background: #fff; box-shadow: 0 2px 16px rgba(0, 0, 0, 0.1);
  font-size: 12px; color: #000; box-sizing: border-box;
}
.head { text-align: center; border-bottom: 2px solid #000; padding-bottom: 8px; margin-bottom: 10px; }
.company { font-size: 13px; letter-spacing: 2px; }
.title { font-size: 20px; font-weight: 700; margin: 6px 0 8px; letter-spacing: 6px; }
.meta-line { display: flex; justify-content: space-between; font-size: 11px; color: #333; }

table { width: 100%; border-collapse: collapse; }
th, td { border: 1px solid #000; padding: 4px 6px; font-size: 11px; vertical-align: middle; }
.head-table th { width: 82px; background: #f4f4f4; font-weight: 600; text-align: center; }
.count-table th { background: #f4f4f4; }
.center { text-align: center; }
.empty { color: #999; padding: 14px; }

.verdict {
  display: flex; align-items: center; gap: 14px;
  margin: 10px 0; padding: 8px 12px; border: 2px solid #000;
}
.verdict-label { font-weight: 600; letter-spacing: 2px; }
.verdict-value { font-size: 18px; font-weight: 700; letter-spacing: 4px; }
.verdict.ok .verdict-value { color: #1a7f37; }
.verdict.ng .verdict-value { color: #c0392b; }
.verdict-reason { flex: 1; font-size: 11px; line-height: 1.5; }

.sec-title { margin: 14px 0 6px; font-weight: 600; font-size: 12px; letter-spacing: 1px; }
.detail-table th { background: #f4f4f4; }
.detail-table tr { page-break-inside: avoid; }
.critical-mark {
  display: inline-block; margin-left: 4px; padding: 0 3px;
  border: 1px solid #c0392b; color: #c0392b; font-size: 9px; line-height: 13px; border-radius: 2px;
}
.ng-text { color: #c0392b; font-weight: 600; }
.judge { padding: 0 4px; border-radius: 2px; font-size: 10px; }
.judge-ok { color: #1a7f37; }
.judge-ng { color: #fff; background: #c0392b; }
.danger { color: #c0392b; font-weight: 700; }

.photos { display: flex; flex-wrap: wrap; gap: 8px; }
.photo { width: 150px; }
.photo img { width: 150px; height: 110px; object-fit: cover; border: 1px solid #ccc; }
.photo-cap { font-size: 9px; color: #666; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.sign-row { display: flex; gap: 18px; margin-top: 22px; font-size: 11px; }
.sign { flex: 1; display: flex; align-items: flex-end; gap: 4px; }
.sign-line { flex: 1; border-bottom: 1px solid #000; height: 14px; }

.foot { margin-top: 14px; padding-top: 6px; border-top: 1px dashed #999; font-size: 9px; color: #666; }

@media print {
  .no-print { display: none !important; }
  .report-page { background: #fff; padding: 0; }
  .paper { margin: 0; box-shadow: none; width: auto; min-height: auto; padding: 0; }
  @page { size: A4; margin: 12mm; }
}
</style>
