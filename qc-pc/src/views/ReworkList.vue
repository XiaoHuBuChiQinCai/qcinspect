<template>
  <el-card>
    <div class="filters">
      <el-input
        v-model="filter.keyword"
        placeholder="票号 / 不良 / 返修人 / 批次号"
        clearable
        style="width: 230px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
        <el-option label="待返修" value="OPEN" />
        <el-option label="返修中" value="DOING" />
        <el-option label="已完工" value="DONE" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-date-picker
        v-model="filter.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开票起始日"
        end-placeholder="截止日"
        style="width: 250px"
        @change="reload"
      />
      <el-checkbox v-model="filter.overdue" @change="reload">只看超期</el-checkbox>
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div class="bar">
      <span class="count">
        共 {{ total }} 张返修票
        <template v-if="overdueCount > 0">
          ，其中 <b class="danger">{{ overdueCount }}</b> 张已超期
        </template>
      </span>
      <div class="spacer" />
      <el-button @click="goDefects">去不良处置</el-button>
    </div>

    <!-- 一句话说清这张表是干什么的：很多系统把「返修」和「复检」混成一件事，
         结果两边都记不全。这里明确：票管件，复检单管合格与否 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="返修票跟件、复检单跟结论"
      description="完工时报「返好几件、返废几件」，返好件数大于 0 会自动开复检单，件数就是返好件数。全部返废时不良直接改判报废。"
      style="margin-bottom: 14px"
    />

    <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
      <el-table-column prop="reworkNo" label="票号" width="145" />
      <el-table-column label="产品 / 批次" width="140">
        <template #default="{ row }">
          <div>{{ row.productName || '—' }}</div>
          <div class="sub">{{ row.batchNo || '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="不良" width="145">
        <template #default="{ row }">
          <div>{{ row.defectName }}</div>
          <div class="sub">{{ row.defectCode || '—' }}<template v-if="row.inspectionNo"> · {{ row.inspectionNo }}</template></div>
        </template>
      </el-table-column>
      <el-table-column label="送修 / 返好 / 返废" width="140" align="center">
        <template #default="{ row }">
          <template v-if="row.status === 'DONE'">
            {{ row.qty }} / <b class="ok">{{ row.reworkedQty }}</b> / <b :class="{ danger: row.scrappedQty > 0 }">{{ row.scrappedQty }}</b>
          </template>
          <template v-else>
            {{ row.qty }} / — / —
          </template>
        </template>
      </el-table-column>
      <el-table-column label="返修人" width="100">
        <template #default="{ row }">{{ row.assignee || '未指派' }}</template>
      </el-table-column>
      <el-table-column label="要求完成" width="110">
        <template #default="{ row }">
          <div :class="{ danger: row.overdue }">{{ row.dueDate || '—' }}</div>
          <div v-if="row.overdue" class="sub danger">超期 {{ row.overdueDays }} 天</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{ row }">
          <!-- 返废原因、取消理由都挂在状态上 hover 看：列表不加备注列，
               但「修废的那件为什么废」不能就此丢了 -->
          <el-tooltip :content="row.finishNote || row.cancelReason || statusText(row.status)"
                      placement="top" :disabled="!row.finishNote && !row.cancelReason">
            <el-tag size="small" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="复检单" width="150">
        <template #default="{ row }">
          <template v-if="row.recheckInspectionNo">
            <el-link type="primary" @click="goInspection(row.recheckInspectionId)">
              {{ row.recheckInspectionNo }}
            </el-link>
            <div class="sub">{{ recheckText(row.recheckResult) }}</div>
          </template>
          <span v-else-if="row.status === 'DONE'" class="sub">全部返废，无需复检</span>
          <span v-else class="sub">完工后自动生成</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="235" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'OPEN' || row.status === 'DOING'">
            <el-button v-if="row.status === 'OPEN'" size="small" @click="start(row)">开工</el-button>
            <el-button size="small" @click="openAssign(row)">改派</el-button>
            <el-button size="small" type="primary" @click="openFinish(row)">完工</el-button>
            <el-button v-if="canReview" size="small" type="danger" plain @click="cancel(row)">取消</el-button>
          </template>
          <span v-else class="sub">—</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page.current"
        v-model:page-size="page.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="load"
        @current-change="load"
      />
    </div>
  </el-card>

  <!-- 改派 -->
  <el-dialog v-model="assignDlg.visible" title="改派返修票" width="420px">
    <el-form label-width="90px">
      <el-form-item label="票号">
        <span>{{ assignDlg.row?.reworkNo }}</span>
      </el-form-item>
      <el-form-item label="送修数量">
        <span>{{ assignDlg.row?.qty }} 件</span>
      </el-form-item>
      <el-form-item label="返修人">
        <el-input v-model="assignDlg.assignee" placeholder="人名或工位，如「三号返修台 老李」" maxlength="64" />
      </el-form-item>
      <el-form-item label="要求完成">
        <el-date-picker v-model="assignDlg.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="assignDlg.visible = false">取消</el-button>
      <el-button type="primary" :loading="assignDlg.saving" @click="doAssign">保存</el-button>
    </template>
  </el-dialog>

  <!-- 完工报数 -->
  <el-dialog v-model="finishDlg.visible" title="返修完工报数" width="480px">
    <el-form label-width="90px">
      <el-form-item label="票号">
        <span>{{ finishDlg.row?.reworkNo }}</span>
        <span class="sub" style="margin-left: 10px">{{ finishDlg.row?.defectName }}</span>
      </el-form-item>
      <el-form-item label="送修数量">
        <b>{{ finishDlg.row?.qty }}</b> 件
      </el-form-item>
      <el-form-item label="返好件数">
        <el-input-number v-model="finishDlg.reworkedQty" :min="0" :max="finishDlg.row?.qty || 0" style="width: 160px" />
      </el-form-item>
      <el-form-item label="返废件数">
        <el-input-number v-model="finishDlg.scrappedQty" :min="0" :max="finishDlg.row?.qty || 0" style="width: 160px" />
        <div class="sub" style="margin-left: 10px">修不好的件，必须报出来，否则它既不算合格也没进报废</div>
      </el-form-item>
      <el-form-item label="对照">
        <span :class="balance.ok ? 'ok' : 'danger'">{{ balance.text }}</span>
      </el-form-item>
      <el-form-item label="说明">
        <el-input
          v-model="finishDlg.finishNote"
          type="textarea"
          :rows="2"
          maxlength="500"
          placeholder="返废原因、换件情况等"
        />
      </el-form-item>
      <el-form-item label="下一步">
        <span class="sub">{{ nextHint }}</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="finishDlg.visible = false">取消</el-button>
      <el-button type="primary" :loading="finishDlg.saving" @click="doFinish">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getStoredUser } from '../api/http'
import { balanceText, checkFinish } from '../utils/rework'

const router = useRouter()
const route = useRoute()
const user = getStoredUser() || {}
// 取消等于把件从账上抹掉，和后端 ReworkController 的判断保持一致
const canReview = computed(() => user.role === 'ADMIN' || user.role === 'REVIEWER')

const rows = ref([])
const total = ref(0)
const overdueCount = ref(0)
const loading = ref(false)
const page = reactive({ current: 1, size: 10 })
const filter = reactive({ keyword: '', status: '', range: null, overdue: false })

function queryParams() {
  const [from, to] = filter.range || []
  return {
    keyword: filter.keyword.trim() || undefined,
    status: filter.status || undefined,
    from: from || undefined,
    to: to || undefined,
    overdue: filter.overdue ? true : undefined
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/rework/page', {
      params: { current: page.current, size: page.size, ...queryParams() }
    })
    rows.value = data.records || []
    total.value = data.total || 0
    // 超期张数单独算：翻到第二页时，第一页的「超期 3 张」不该跟着变
    overdueCount.value = filter.overdue ? data.total || 0 : (await countOverdue())
  } finally {
    loading.value = false
  }
}

/**
 * 超期张数走一次轻量查询（size=1，只看 total）。
 * 不把它做成一个专门的统计接口：多一个接口就多一处口径，
 * 而「有多少张超期」和列表用的是同一个筛选条件，没必要分家。
 */
async function countOverdue() {
  try {
    const data = await http.get('/rework/page', { params: { current: 1, size: 1, overdue: true } })
    return data.total || 0
  } catch {
    return 0
  }
}

function reload() {
  page.current = 1
  load()
}

function resetFilters() {
  Object.assign(filter, { keyword: '', status: '', range: null, overdue: false })
  reload()
}

function rowClass({ row }) {
  return row.overdue ? 'overdue-row' : ''
}

function tagType(status) {
  return { OPEN: 'warning', DOING: 'primary', DONE: 'success' }[status] || 'info'
}

function statusText(status) {
  return { OPEN: '待返修', DOING: '返修中', DONE: '已完工', CANCELLED: '已取消' }[status] || status
}

function recheckText(result) {
  return { PASS: '复检合格', FAIL: '复检不合格', NA: '待判定' }[result] || '待判定'
}

function goInspection(id) {
  router.push(`/inspection/${id}`)
}

function goDefects() {
  router.push('/defects')
}

/* ---------------- 开工 ---------------- */

async function start(row) {
  await http.post(`/rework/${row.id}/start`)
  ElMessage.success('已开工')
  await load()
}

/* ---------------- 改派 ---------------- */

const assignDlg = reactive({ visible: false, row: null, assignee: '', dueDate: '', saving: false })

function openAssign(row) {
  Object.assign(assignDlg, {
    visible: true,
    row,
    assignee: row.assignee || '',
    dueDate: row.dueDate || '',
    saving: false
  })
}

async function doAssign() {
  if (!assignDlg.assignee.trim()) {
    ElMessage.warning('返修人不能为空，写工位也行，但要能对上人')
    return
  }
  assignDlg.saving = true
  try {
    await http.post(`/rework/${assignDlg.row.id}/assign`, {
      assignee: assignDlg.assignee.trim(),
      dueDate: assignDlg.dueDate || undefined
    })
    ElMessage.success('已改派')
    assignDlg.visible = false
    await load()
  } finally {
    assignDlg.saving = false
  }
}

/* ---------------- 完工 ---------------- */

const finishDlg = reactive({
  visible: false,
  row: null,
  reworkedQty: 0,
  scrappedQty: 0,
  finishNote: '',
  saving: false
})

const balance = computed(() =>
  balanceText(finishDlg.row?.qty, finishDlg.reworkedQty, finishDlg.scrappedQty)
)

const nextHint = computed(() => {
  const r = finishDlg.reworkedQty || 0
  const s = finishDlg.scrappedQty || 0
  if (r > 0 && s > 0) return `会自动开一张 ${r} 件的复检单，报废的 ${s} 件计入返工损耗`
  if (r > 0) return `会自动开一张 ${r} 件的复检单，Ac=0（返工件必须全部合格）`
  if (r === 0 && s > 0) return '全部返废：不良会直接改判报废，不开复检单'
  return '填完报数再看这里'
})

function openFinish(row) {
  Object.assign(finishDlg, {
    visible: true,
    row,
    // 默认「全部修好」：多数返修是成功的，让人从 0 开始数反而容易填错
    reworkedQty: row.qty,
    scrappedQty: 0,
    finishNote: '',
    saving: false
  })
}

async function doFinish() {
  const err = checkFinish(finishDlg.row.qty, finishDlg.reworkedQty, finishDlg.scrappedQty)
  if (err) {
    ElMessage.warning(err)
    return
  }
  if (finishDlg.scrappedQty > 0 && !finishDlg.finishNote.trim()) {
    ElMessage.warning('有返废件，请在说明里写清原因')
    return
  }
  finishDlg.saving = true
  try {
    const res = await http.post(`/rework/${finishDlg.row.id}/finish`, {
      reworkedQty: finishDlg.reworkedQty,
      scrappedQty: finishDlg.scrappedQty,
      finishNote: finishDlg.finishNote.trim() || undefined
    })
    finishDlg.visible = false
    await load()
    if (res.recheckInspectionNo) {
      ElMessageBox.confirm(
        `复检单 ${res.recheckInspectionNo} 已生成，共 ${finishDlg.reworkedQty} 件。现在去填实测值吗？`,
        '返修已完工',
        { confirmButtonText: '去复检', cancelButtonText: '稍后', type: 'success' }
      )
        .then(() => goInspection(res.recheckInspectionId))
        .catch(() => {})
    } else {
      ElMessage.success('已完工，全部返废，不良已改判报废')
    }
  } finally {
    finishDlg.saving = false
  }
}

/* ---------------- 取消 ---------------- */

async function cancel(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt(
      `取消后这 ${row.qty} 件不再挂在返修区，不良会退回「待处理」等重新处置。请写清原因：`,
      `取消 ${row.reworkNo}`,
      {
        inputType: 'textarea',
        inputPlaceholder: '如：改为直接报废 / 转外协返修 / 客户同意让步接收',
        inputValidator: v => (v && v.trim().length >= 2 ? true : '原因至少写两个字'),
        confirmButtonText: '确认取消',
        cancelButtonText: '返回'
      }
    )
    reason = value
  } catch {
    return
  }
  await http.post(`/rework/${row.id}/cancel`, { reason: reason.trim() })
  ElMessage.success('已取消')
  await load()
}

onMounted(() => {
  // 从不良页点票号过来会带 keyword，直接落到那张票上
  if (route.query.keyword) {
    filter.keyword = String(route.query.keyword)
  }
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
.sub { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.danger { color: var(--el-color-danger); }
.ok { color: var(--el-color-success); }
:deep(.overdue-row) { background: var(--el-color-danger-light-9); }
</style>
