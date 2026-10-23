<template>
  <el-card>
    <div class="filters">
      <el-input
        v-model="filter.keyword"
        placeholder="客诉单号 / 客户 / 批次号"
        clearable
        style="width: 220px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
        <el-option label="待处理" value="OPEN" />
        <el-option label="处理中" value="PROCESSING" />
        <el-option label="已关闭" value="CLOSED" />
      </el-select>
      <el-select v-model="filter.severity" placeholder="全部严重度" clearable style="width: 130px" @change="reload">
        <el-option label="严重" value="HIGH" />
        <el-option label="一般" value="MEDIUM" />
        <el-option label="轻微" value="LOW" />
      </el-select>
      <el-date-picker
        v-model="filter.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="客诉起始日"
        end-placeholder="截止日"
        style="width: 250px"
        @change="reload"
      />
      <el-checkbox v-model="filter.open" label="只看未关闭" @change="reload" />
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div class="bar">
      <span class="count">共 {{ total }} 条客诉，未关闭 {{ openCount }} 条</span>
      <div class="spacer" />
      <el-button @click="exportCsv">导出 CSV</el-button>
      <el-button type="primary" @click="openCreate">登记客诉</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div class="line"><b>问题描述：</b>{{ row.description }}</div>
            <div class="line"><b>根本原因：</b>{{ row.rootCause || '—（未关闭，尚未填写）' }}</div>
            <div class="line"><b>纠正预防措施：</b>{{ row.action || '—' }}</div>
            <div class="line"><b>责任归属：</b>{{ row.responsibility || '—' }}</div>
            <div class="line" v-if="row.remark"><b>备注：</b>{{ row.remark }}</div>
            <div class="line" v-if="row.closedAt">
              <b>关闭：</b>{{ row.closedBy }} 于 {{ fmt(row.closedAt) }}
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="complaintNo" label="客诉单号" width="150" />
      <el-table-column label="客诉日期" width="110">
        <template #default="{ row }">{{ row.complaintDate || '—' }}</template>
      </el-table-column>
      <el-table-column prop="customer" label="客户" min-width="140" />
      <el-table-column prop="batchNo" label="批次号" width="140" />
      <el-table-column prop="productName" label="产品" min-width="110" />
      <el-table-column label="类别" width="90">
        <template #default="{ row }">{{ categoryText(row.category) }}</template>
      </el-table-column>
      <el-table-column label="严重度" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="severityType(row.severity)">{{ severityText(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="涉及数量" width="90">
        <template #default="{ row }">{{ row.qty == null ? '—' : row.qty }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="登记人" width="100" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'OPEN'"
            size="small"
            @click="startIt(row)"
          >
            受理
          </el-button>
          <el-button
            v-if="row.status !== 'CLOSED' && canClose"
            size="small"
            type="primary"
            plain
            @click="openClose(row)"
          >
            关闭
          </el-button>
          <el-button size="small" @click="goTrace(row)">追溯</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page.current"
        v-model:page-size="page.size"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <!-- 登记客诉 -->
    <el-dialog v-model="createVisible" title="登记客户投诉" width="620px" @closed="resetCreate">
      <el-form :model="form" label-width="96px">
        <el-form-item label="被投诉批次" required>
          <el-select
            v-model="form.batchId"
            filterable
            placeholder="输入批次号搜索"
            style="width: 100%"
            @change="onBatchChange"
          >
            <el-option
              v-for="b in batches"
              :key="b.id"
              :label="`${b.batchNo}（${b.qty} 件 / ${b.inspectType}）`"
              :value="b.id"
            />
          </el-select>
          <div class="hint">必填。没有批次的客诉无法追溯，也没法定责</div>
        </el-form-item>
        <el-form-item label="关联出货单">
          <el-select
            v-model="form.shipmentId"
            clearable
            placeholder="可不选，客户常常说不清是哪一单"
            style="width: 100%"
            :disabled="!form.batchId"
          >
            <el-option
              v-for="s in shipments"
              :key="s.id"
              :label="`${s.shipmentNo} / ${s.customer} / ${s.qty} 件`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户" required>
          <el-input v-model="form.customer" placeholder="客户名称，客诉时这是唯一能对上的线索" />
        </el-form-item>
        <el-form-item label="客诉日期" required>
          <el-date-picker
            v-model="form.complaintDate"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="d => d.getTime() > Date.now()"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category" style="width: 100%">
            <el-option label="外观" value="APP" />
            <el-option label="尺寸" value="SIZE" />
            <el-option label="性能" value="PERF" />
            <el-option label="包装" value="PACK" />
            <el-option label="标识" value="MARK" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-radio-group v-model="form.severity">
            <el-radio-button label="HIGH">严重</el-radio-button>
            <el-radio-button label="MEDIUM">一般</el-radio-button>
            <el-radio-button label="LOW">轻微</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="涉及数量">
          <el-input-number v-model="form.qty" :min="1" :step="1" placeholder="报不准就留空" />
          <span class="hint inline">客户报不准时留空，别瞎填</span>
        </el-form-item>
        <el-form-item label="问题描述" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="客户原话：哪一批、什么现象、多少件、什么时候发现的"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="客户要求（退货 / 换货 / 索赔 / 只要答复）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 关闭客诉 -->
    <el-dialog v-model="closeVisible" :title="`关闭 ${closing?.complaintNo || ''}`" width="600px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="三项都必填：只有写了根因、措施、责任方，这条记录明年才还有人看得懂"
        class="mb"
      />
      <el-form :model="closeForm" label-width="110px">
        <el-form-item label="根本原因" required>
          <el-input v-model="closeForm.rootCause" type="textarea" :rows="2"
                    placeholder="如：来料批次硬度超上限，供应商热处理工艺未按图执行" />
        </el-form-item>
        <el-form-item label="纠正预防措施" required>
          <el-input v-model="closeForm.action" type="textarea" :rows="2"
                    placeholder="如：要求供应商提供每批硬度报告；本厂 IQC 增加硬度抽检" />
        </el-form-item>
        <el-form-item label="责任归属" required>
          <el-input v-model="closeForm.responsibility"
                    placeholder="供应商 / 本厂制程 / 设计 / 运输 / 客户方" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitClose">确认关闭</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getStoredUser } from '../api/http'
import { downloadCsv } from '../api/download'

const route = useRoute()
const router = useRouter()
const user = getStoredUser() || {}
/** 从工作台点「去处理」带过来的客诉 id，只用来把那一行标黄，不做筛选 */
const focusId = route.query.focus ? Number(route.query.focus) : null
/** 关闭客诉要求 ADMIN / REVIEWER：问题是不是真解决了，不能由接电话的人自己说 */
const canClose = computed(() => user.role === 'ADMIN' || user.role === 'REVIEWER')

const rows = ref([])
const total = ref(0)
const openCount = ref(0)
const loading = ref(false)
const saving = ref(false)
const page = reactive({ current: 1, size: 10 })
const filter = reactive({ keyword: '', status: '', severity: '', range: null, open: false })

const batches = ref([])
const shipments = ref([])
const createVisible = ref(false)
const closeVisible = ref(false)
const closing = ref(null)
const form = reactive({
  batchId: null, shipmentId: null, customer: '', complaintDate: today(),
  category: 'APP', severity: 'MEDIUM', qty: null, description: '', remark: ''
})
const closeForm = reactive({ rootCause: '', action: '', responsibility: '' })

function today() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

function categoryText(c) {
  return { APP: '外观', SIZE: '尺寸', PERF: '性能', PACK: '包装', MARK: '标识', OTHER: '其他' }[c] || c || '—'
}

function severityText(s) {
  return { HIGH: '严重', MEDIUM: '一般', LOW: '轻微' }[s] || s || '—'
}

function severityType(s) {
  return { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[s] || 'info'
}

function statusText(s) {
  return { OPEN: '待处理', PROCESSING: '处理中', CLOSED: '已关闭' }[s] || s
}

function statusType(s) {
  return { OPEN: 'danger', PROCESSING: 'warning', CLOSED: 'success' }[s] || 'info'
}

function rowClass({ row }) {
  if (row.id && row.id === focusId) return 'focus-row'
  if (row.status === 'CLOSED') return 'closed-row'
  return row.severity === 'HIGH' ? 'high-row' : ''
}

function queryParams() {
  const [from, to] = filter.range || []
  return {
    keyword: filter.keyword.trim() || undefined,
    status: filter.status || undefined,
    severity: filter.severity || undefined,
    from: from || undefined,
    to: to || undefined,
    open: filter.open ? true : undefined
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/complaints/page', {
      params: { current: page.current, size: page.size, ...queryParams() }
    })
    rows.value = data.records || []
    total.value = data.total || 0
    await loadOpenCount()
  } finally {
    loading.value = false
  }
}

/** 未关闭数单独取：列表被筛选时上面的统计不该跟着变 */
async function loadOpenCount() {
  try {
    const s = await http.get('/complaints/summary')
    openCount.value = Number(s?.open || 0)
  } catch {
    openCount.value = 0
  }
}

function reload() {
  page.current = 1
  load()
}

function resetFilters() {
  Object.assign(filter, { keyword: '', status: '', severity: '', range: null, open: false })
  reload()
}

function exportCsv() {
  downloadCsv('/export/complaints', queryParams(), '客户投诉')
}

function goTrace(row) {
  if (!row.batchId) {
    ElMessage.warning('这条客诉没有关联批次')
    return
  }
  router.push({ path: '/trace', query: { batchId: row.batchId } })
}

async function openCreate() {
  createVisible.value = true
  if (!batches.value.length) {
    batches.value = await http.get('/batches')
  }
}

function resetCreate() {
  Object.assign(form, {
    batchId: null, shipmentId: null, customer: '', complaintDate: today(),
    category: 'APP', severity: 'MEDIUM', qty: null, description: '', remark: ''
  })
  shipments.value = []
}

/** 选了批次就把该批次的出货单捞出来，让「投诉的是哪一单」可以直接点 */
async function onBatchChange(id) {
  form.shipmentId = null
  shipments.value = id ? await http.get('/shipments', { params: { batchId: id } }) : []
}

async function submitCreate() {
  if (!form.batchId) return ElMessage.warning('请选择被投诉批次')
  if (!form.customer.trim()) return ElMessage.warning('请填写客户名称')
  if (!form.description.trim()) return ElMessage.warning('请描述客户反馈的问题')
  saving.value = true
  try {
    await http.post('/complaints', { ...form })
    ElMessage.success('已登记，可在「未关闭」里跟踪')
    createVisible.value = false
    reload()
  } finally {
    saving.value = false
  }
}

async function startIt(row) {
  await http.post(`/complaints/${row.id}/start`)
  ElMessage.success('已受理')
  await load()
}

function openClose(row) {
  closing.value = row
  Object.assign(closeForm, {
    rootCause: row.rootCause || '',
    action: row.action || '',
    responsibility: row.responsibility || ''
  })
  closeVisible.value = true
}

async function submitClose() {
  if (!closeForm.rootCause.trim()) return ElMessage.warning('请填写根本原因')
  if (!closeForm.action.trim()) return ElMessage.warning('请填写纠正预防措施')
  if (!closeForm.responsibility.trim()) return ElMessage.warning('请填写责任归属')
  saving.value = true
  try {
    await http.post(`/complaints/${closing.value.id}/close`, { ...closeForm })
    ElMessage.success('已关闭')
    closeVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px;
           padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.detail { padding: 6px 18px 10px; font-size: 13px; line-height: 1.9; }
.detail .line { color: var(--el-text-color-regular); }
.hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.hint.inline { margin-left: 10px; }
.mb { margin-bottom: 14px; }
:deep(.closed-row) { color: var(--el-text-color-placeholder); }
:deep(.high-row) { background: var(--el-color-danger-light-9); }
:deep(.focus-row) { background: var(--el-color-warning-light-9); }
</style>
