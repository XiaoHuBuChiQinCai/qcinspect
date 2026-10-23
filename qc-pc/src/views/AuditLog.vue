<template>
  <el-card>
    <div class="filters">
      <el-select v-model="filter.action" placeholder="全部动作" clearable style="width: 140px" @change="reload">
        <el-option label="建单" value="CREATE" />
        <el-option label="提交" value="SUBMIT" />
        <el-option label="审核通过" value="REVIEW" />
        <el-option label="审核退回" value="REJECT" />
        <el-option label="解锁重开" value="REOPEN" />
        <el-option label="归档" value="CLOSE" />
        <el-option label="作废" value="VOID" />
      </el-select>
      <el-input
        v-model="filter.keyword"
        placeholder="检验单号 / 批次号"
        clearable
        style="width: 190px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-input
        v-model="filter.operator"
        placeholder="操作人"
        clearable
        style="width: 140px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-date-picker
        v-model="filter.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="起始日"
        end-placeholder="截止日"
        style="width: 250px"
        @change="reload"
      />
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div class="bar">
      <span class="count">共 {{ total }} 条</span>
      <span class="hint">作废、解锁这类动作是不可逆的，理由都记在这儿</span>
      <div class="spacer" />
      <el-tooltip content="导出当前筛选条件下的日志，内审取证用">
        <el-button type="primary" plain @click="exportLedger">导出 CSV</el-button>
      </el-tooltip>
    </div>

    <el-table :data="rows" v-loading="loading">
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="动作" width="110">
        <template #default="{ row }">
          <el-tag :type="actionType(row.action)" size="small">{{ actionText(row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="检验单号" width="170">
        <template #default="{ row }">
          <el-button v-if="row.inspectionId" link type="primary" @click="go(row.inspectionId)">
            {{ row.inspectionNo || '（已删除）' }}
          </el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="批次号" width="150">
        <template #default="{ row }">{{ row.batchNo || '—' }}</template>
      </el-table-column>
      <el-table-column label="产品" min-width="120">
        <template #default="{ row }">{{ row.productName || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态变化" width="150">
        <template #default="{ row }">
          <span class="st">{{ statusText(row.fromStatus) || '—' }}</span>
          <span class="arrow">→</span>
          <span class="st">{{ statusText(row.toStatus) || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作人" width="110">
        <template #default="{ row }">{{ row.operator || '—' }}</template>
      </el-table-column>
      <el-table-column label="理由 / 意见" min-width="220">
        <template #default="{ row }">
          <span :class="row.reason ? '' : 'muted'">{{ row.reason || '—' }}</span>
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
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { downloadCsv } from '../api/download'

const router = useRouter()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ current: 1, size: 20 })
const filter = reactive({ action: '', keyword: '', operator: '', range: null })

const actionText = a =>
  ({ CREATE: '建单', SUBMIT: '提交', REVIEW: '审核通过', REJECT: '审核退回',
     REOPEN: '解锁重开', CLOSE: '归档', VOID: '作废' }[a] || a)

const actionType = a =>
  ({ CREATE: 'info', SUBMIT: '', REVIEW: 'success', REJECT: 'warning',
     REOPEN: 'warning', CLOSE: 'success', VOID: 'danger' }[a] || 'info')

const statusText = s =>
  ({ DRAFT: '草稿', SUBMITTED: '待审核', CLOSED: '已归档', VOID: '已作废' }[s] || s || '')

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

/** 列表与导出共用一套条件，避免「屏幕 20 条、CSV 200 条」 */
function queryParams() {
  const [from, to] = filter.range || []
  return {
    action: filter.action || undefined,
    keyword: filter.keyword.trim() || undefined,
    operator: filter.operator.trim() || undefined,
    from: from || undefined,
    to: to || undefined
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/audit/logs', {
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
  Object.assign(filter, { action: '', keyword: '', operator: '', range: null })
  reload()
}

function go(id) {
  router.push(`/inspection/${id}`)
}

async function exportLedger() {
  await downloadCsv('/export/audit-logs', queryParams(), '操作日志')
}

onMounted(load)
</script>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px;
           padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
.spacer { flex: 1; }
.pager { margin-top: 14px; display: flex; justify-content: flex-end; }
.st { color: var(--el-text-color-regular); }
.arrow { margin: 0 6px; color: var(--el-text-color-placeholder); }
.muted { color: var(--el-text-color-placeholder); }
</style>
