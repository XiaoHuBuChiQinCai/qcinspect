<template>
  <el-card>
    <div class="filters">
      <el-input
        v-model="filter.keyword"
        placeholder="出货单号 / 客户 / 批次号"
        clearable
        style="width: 220px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
        <el-option label="正常" value="NORMAL" />
        <el-option label="已作废" value="VOID" />
      </el-select>
      <el-date-picker
        v-model="filter.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="出货起始日"
        end-placeholder="截止日"
        style="width: 250px"
        @change="reload"
      />
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div class="bar">
      <span class="count">共 {{ total }} 条出货记录</span>
      <div class="spacer" />
      <el-button @click="goTrace">追溯查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
      <el-table-column prop="shipmentNo" label="出货单号" width="160" />
      <el-table-column prop="batchNo" label="批次号" width="150" />
      <el-table-column prop="productName" label="产品" min-width="110" />
      <el-table-column prop="customer" label="客户" min-width="150" />
      <el-table-column prop="qty" label="数量" width="80" />
      <el-table-column label="出货时间" width="160">
        <template #default="{ row }">{{ fmt(row.shippedAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.status === 'VOID'"
            :content="`${row.voidedBy || '—'} ${fmt(row.voidedAt)} 作废：${row.voidReason || '未填理由'}`"
            placement="top"
          >
            <el-tag type="danger" size="small">已作废</el-tag>
          </el-tooltip>
          <el-tag v-else type="success" size="small">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="160">
        <template #default="{ row }">
          <span :class="row.remark && row.remark.startsWith('[例外放行]') ? 'exception' : ''">
            {{ row.remark || '—' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="登记人" width="110" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="trace(row)">追溯</el-button>
          <el-button
            v-if="row.status === 'NORMAL' && canVoid(row)"
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
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getStoredUser } from '../api/http'

const router = useRouter()
const user = getStoredUser() || {}
const isAdmin = computed(() => user.role === 'ADMIN')

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ current: 1, size: 10 })
const filter = reactive({ keyword: '', status: '', range: null })

function displayName() {
  return user.realName || user.username || ''
}

/**
 * 谁能作废：管理员随便销，普通用户只能销自己登记的单。
 * 后端也做了同样的判断，这里只是不把点了必然失败的按钮摆出来。
 */
function canVoid(row) {
  return isAdmin.value || (!!row.createdBy && row.createdBy === displayName())
}

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

function rowClass({ row }) {
  return row.status === 'VOID' ? 'void-row' : ''
}

function queryParams() {
  const [from, to] = filter.range || []
  return {
    keyword: filter.keyword.trim() || undefined,
    status: filter.status || undefined,
    from: from || undefined,
    to: to || undefined
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/shipments/page', {
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
  Object.assign(filter, { keyword: '', status: '', range: null })
  reload()
}

function trace(row) {
  router.push({ path: '/trace', query: { shipmentNo: row.shipmentNo } })
}

function goTrace() {
  router.push('/trace')
}

/**
 * 作废不是删除：单据留着，状态改成 VOID 并带上理由。
 * 这样「客户临时取消」和「压根没发过」在数据上分得清——
 * 客诉时这两件事的处理方式完全不同。
 */
async function voidIt(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt(
      `作废后这 ${row.qty} 件不再计入批次累计出货量，但记录会保留。请写清原因：`,
      `作废 ${row.shipmentNo}`,
      {
        inputType: 'textarea',
        inputPlaceholder: '如：客户取消订单 / 数量登记错误 / 重复登记',
        inputValidator: v => (v && v.trim().length >= 2 ? true : '原因至少写两个字，否则以后没人看得懂'),
        confirmButtonText: '确认作废',
        cancelButtonText: '取消'
      }
    )
    reason = value
  } catch {
    return
  }
  await http.post(`/shipments/${row.id}/void`, { reason: reason.trim() })
  ElMessage.success('已作废，批次可发数量已回退')
  await load()
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
.exception { color: var(--el-color-warning); }
:deep(.void-row) { color: var(--el-text-color-placeholder); }
:deep(.void-row) .el-tag--danger { opacity: 0.85; }
</style>
