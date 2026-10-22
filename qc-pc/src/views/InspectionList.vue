<template>
  <el-card>
    <div class="filters">
      <el-input
        v-model="filter.keyword"
        placeholder="单号 / 批次号"
        clearable
        style="width: 190px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="待审核" value="SUBMITTED" />
        <el-option label="已归档" value="CLOSED" />
        <el-option label="已作废" value="VOID" />
      </el-select>
      <el-select v-model="filter.result" placeholder="全部结论" clearable style="width: 120px" @change="reload">
        <el-option label="合格" value="PASS" />
        <el-option label="不合格" value="FAIL" />
        <el-option label="未判定" value="NA" />
      </el-select>
      <el-select
        v-model="filter.productId"
        placeholder="全部产品"
        clearable
        filterable
        style="width: 170px"
        @change="reload"
      >
        <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
      </el-select>
      <el-input
        v-model="filter.inspector"
        placeholder="检验员"
        clearable
        style="width: 130px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-date-picker
        v-model="filter.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="检验起始日"
        end-placeholder="截止日"
        style="width: 250px"
        @change="reload"
      />
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div class="bar">
      <span class="count">共 {{ total }} 张单</span>
      <div class="spacer" />
      <el-tooltip content="导出当前筛选条件下的检验单台账，Excel 可直接打开">
        <el-button type="primary" plain @click="exportLedger">导出台账 CSV</el-button>
      </el-tooltip>
    </div>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="inspectionNo" label="检验单号" width="160" />
      <el-table-column prop="batchNo" label="批次号" width="150" />
      <el-table-column prop="productName" label="产品" min-width="120" />
      <el-table-column label="判定" width="100">
        <template #default="{ row }">
          <el-tag :type="resultType(row.result)" size="small">{{ resultText(row.result) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="合格 / 不合格（件）" width="150">
        <template #default="{ row }">
          <span class="ok">{{ row.passedQty }}</span>
          <span class="sep">/</span>
          <span :class="row.failedQty > 0 ? 'ng' : ''">{{ row.failedQty }}</span>
          <span class="sub"> of {{ row.sampleQty }}</span>
        </template>
      </el-table-column>
      <el-table-column label="NG 行数" width="110">
        <template #default="{ row }">
          <span :class="row.criticalNgCount > 0 ? 'ng-bold' : ''">
            {{ row.ngCount }}
            <span v-if="row.criticalNgCount > 0" class="sub">（关键 {{ row.criticalNgCount }}）</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="inspectorName" label="检验员" width="110" />
      <el-table-column label="检验时间" width="160">
        <template #default="{ row }">{{ fmt(row.inspectedAt) }}</template>
      </el-table-column>
      <el-table-column label="审核人" width="110">
        <template #default="{ row }">{{ row.reviewedBy || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="310" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="open(row)">查看</el-button>
          <el-button size="small" @click="print(row)">报告</el-button>
          <el-button
            v-if="row.status === 'SUBMITTED' && canReview"
            size="small"
            type="success"
            @click="openReview(row)"
          >
            审核
          </el-button>
          <el-button
            v-if="(row.status === 'DRAFT' || row.status === 'SUBMITTED') && canReview"
            size="small"
            type="danger"
            plain
            @click="voidIt(row)"
          >
            作废
          </el-button>
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

    <!-- 审核（与工作台共用同一份对话框，规则只写一遍） -->
    <ReviewDialog v-model="reviewVisible" :row="target" @done="load" />
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getStoredUser } from '../api/http'
import { downloadCsv } from '../api/download'
import ReviewDialog from '../components/ReviewDialog.vue'

const router = useRouter()
const user = getStoredUser() || {}
const canReview = computed(() => user.role === 'ADMIN' || user.role === 'REVIEWER')

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const products = ref([])
const page = reactive({ current: 1, size: 10 })
// 筛选条件集中在一处，导出时原样带上，避免「屏幕 3 条、CSV 30 条」
const filter = reactive({ keyword: '', status: '', result: '', productId: undefined, inspector: '', range: null })

const reviewVisible = ref(false)
const target = ref({})

const resultText = r => ({ PASS: '合格', FAIL: '不合格', NA: '未判定' }[r] || r || '—')
const resultType = r => ({ PASS: 'success', FAIL: 'danger', NA: 'info' }[r] || 'info')
const statusText = s =>
  ({ DRAFT: '草稿', SUBMITTED: '待审核', CLOSED: '已归档', VOID: '已作废' }[s] || s)
const statusType = s =>
  ({ DRAFT: 'info', SUBMITTED: 'warning', CLOSED: 'success', VOID: 'danger' }[s] || 'info')

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

/** 当前筛选条件 → 请求参数。列表分页和 CSV 导出共用，保证两边口径一致 */
function queryParams() {
  const [from, to] = filter.range || []
  return {
    status: filter.status || undefined,
    result: filter.result || undefined,
    productId: filter.productId ?? undefined,
    keyword: filter.keyword.trim() || undefined,
    inspector: filter.inspector.trim() || undefined,
    from: from || undefined,
    to: to || undefined
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/inspections/page', {
      params: { current: page.current, size: page.size, ...queryParams() }
    })
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  page.current = 1
  load()
}

function resetFilters() {
  Object.assign(filter, { keyword: '', status: '', result: '', productId: undefined, inspector: '', range: null })
  reload()
}

function open(row) {
  router.push(`/inspection/${row.id}`)
}

function print(row) {
  router.push(`/report/${row.id}`)
}

function openReview(row) {
  target.value = row
  reviewVisible.value = true
}

async function voidIt(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt('作废后单据不可恢复，请填写原因', `作废 ${row.inspectionNo}`, {
      inputPattern: /\S+/,
      inputErrorMessage: '原因不能为空'
    })
    reason = value
  } catch {
    return
  }
  await http.post(`/inspections/${row.id}/void?reason=${encodeURIComponent(reason)}`)
  ElMessage.success('已作废')
  await load()
}

async function exportLedger() {
  await downloadCsv('/export/inspections', queryParams(), '检验单台账')
}

async function loadProducts() {
  products.value = await http.get('/products')
}

onMounted(() => {
  loadProducts()
  load()
})
</script>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px;
           padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.ok { color: var(--el-color-success); font-weight: 600; }
.ng { color: var(--el-color-danger); font-weight: 600; }
.ng-bold { color: var(--el-color-danger); font-weight: 600; }
.sep { margin: 0 6px; color: var(--el-text-color-secondary); }
.sub { font-size: 12px; color: var(--el-text-color-secondary); }
.mb { margin-bottom: 14px; }
.hint { margin-top: 10px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
</style>
