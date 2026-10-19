<template>
  <div>
    <!-- 帕累托 -->
    <el-card class="mb">
      <template #header>
        <div class="card-head">
          <span>不良帕累托（近 90 天已提交单据）</span>
          <el-button size="small" @click="loadPareto">刷新</el-button>
        </div>
      </template>
      <div v-if="!pareto.length" class="empty">还没有不良记录</div>
      <div v-else class="pareto">
        <div v-for="(p, i) in pareto" :key="p.code" class="row">
          <span class="idx">{{ i + 1 }}</span>
          <span class="name">{{ p.name }}</span>
          <div class="bar-wrap">
            <div class="bar" :style="{ width: (p.percent || 0) + '%' }" />
          </div>
          <span class="qty">{{ p.qty }}</span>
          <span class="pct">{{ p.percent }}%</span>
          <span class="cum">累计 {{ p.cumulative_percent }}%</span>
        </div>
      </div>
    </el-card>

    <!-- 处置列表 -->
    <el-card>
      <template #header>
        <div class="card-head">
          <span>不良处置</span>
          <el-select v-model="statusFilter" size="small" style="width: 160px" @change="load">
            <el-option label="全部" value="" />
            <el-option label="待处理" value="PENDING" />
            <el-option label="处理中" value="PROCESSING" />
            <el-option label="已完成" value="DONE" />
            <el-option label="已让步" value="WAIVED" />
          </el-select>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="检验单" width="160">
          <template #default="{ row }">#{{ row.inspectionId }} / 第 {{ row.pieceNo }} 件</template>
        </el-table-column>
        <el-table-column prop="defectName" label="不良" min-width="120" />
        <el-table-column prop="defectCode" label="代码" width="110" />
        <el-table-column prop="defectQty" label="数量" width="80" />
        <el-table-column label="处置" width="110">
          <template #default="{ row }">{{ dispositionText(row.disposition) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.dispositionStatus)">
              {{ statusText(row.dispositionStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="复检单" width="100">
          <template #default="{ row }">
            <el-link v-if="row.recheckInspectionId" type="primary" @click="goInspection(row.recheckInspectionId)">
              {{ row.recheckInspectionId }}
            </el-link>
            <span v-else class="empty-inline">—</span>
          </template>
        </el-table-column>
        <el-table-column label="处置人 / 理由" min-width="180">
          <template #default="{ row }">
            <div>{{ row.dispositionBy || '—' }}</div>
            <div class="sub">{{ row.concessionReason || row.dispositionNote || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <template v-if="row.dispositionStatus === 'PENDING' || row.dispositionStatus === 'PROCESSING'">
              <el-button size="small" @click="dispose(row, 'REWORK', 'PROCESSING')">返工</el-button>
              <el-button size="small" @click="dispose(row, 'RETURN', 'PROCESSING')">退货</el-button>
              <el-button size="small" type="warning" @click="dispose(row, 'SCRAP', 'DONE')">报废</el-button>
              <el-button size="small" type="danger" @click="openConcession(row)">让步接收</el-button>
            </template>
            <el-button v-else size="small" disabled>已闭环</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 让步接收 -->
    <el-dialog v-model="conVisible" title="让步接收审批" width="480px">
      <el-alert type="warning" :closable="false" class="mb"
                title="让步接收是 ISO 体系里唯一需要授权人签字放行的处置方式，理由会被审核追问" />
      <el-form label-width="90px">
        <el-form-item label="理由" required>
          <el-input v-model="conReason" type="textarea" :rows="3"
                    placeholder="如：该尺寸超差 0.02mm，客户确认不影响装配，申请让步放行" />
        </el-form-item>
        <el-form-item label="授权人" required>
          <el-input v-model="conApprover" placeholder="品质经理姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="conVisible = false">取消</el-button>
        <el-button type="primary" @click="doConcession">确认让步</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const router = useRouter()
const rows = ref([])
const pareto = ref([])
const loading = ref(false)
const statusFilter = ref('')

const conVisible = ref(false)
const conReason = ref('')
const conApprover = ref('')
const conTarget = ref(null)

function statusText(s) {
  return { PENDING: '待处理', PROCESSING: '处理中', DONE: '已完成', WAIVED: '已让步' }[s] || s
}

function statusType(s) {
  return { PENDING: 'danger', PROCESSING: 'warning', DONE: 'success', WAIVED: 'info' }[s] || 'info'
}

function dispositionText(d) {
  return { REWORK: '返工', SCRAP: '报废', CONCESSION: '让步接收', RETURN: '退货' }[d] || '未定'
}

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/defects', {
      params: statusFilter.value ? { status: statusFilter.value } : {}
    })
  } finally {
    loading.value = false
  }
}

async function loadPareto() {
  const from = new Date(Date.now() - 90 * 86400000).toISOString()
  pareto.value = await http.get('/defects/pareto', { params: { from } })
}

async function dispose(row, disposition, status) {
  if (disposition === 'REWORK' && status === 'PROCESSING') {
    const { value } = await ElMessageBox.prompt('返工说明（可留空）', '开始返工', { inputPlaceholder: '返工工序 / 负责人' })
    await http.post(`/defects/${row.id}/dispose`, { disposition, status, note: value, operator: '当前用户' })
  } else {
    await http.post(`/defects/${row.id}/dispose`, { disposition, status, operator: '当前用户' })
  }
  ElMessage.success('已提交处置')
  await load()
}

function openConcession(row) {
  conTarget.value = row
  conReason.value = ''
  conApprover.value = ''
  conVisible.value = true
}

async function doConcession() {
  if (!conReason.value.trim() || !conApprover.value.trim()) {
    ElMessage.warning('理由和授权人都是必填，审核时会看这两项')
    return
  }
  await http.post(`/defects/${conTarget.value.id}/concession`, {
    reason: conReason.value,
    approvedBy: conApprover.value
  })
  ElMessage.success('已记录让步接收')
  conVisible.value = false
  await load()
}

function goInspection(id) {
  router.push(`/inspection/${id}`)
}

onMounted(async () => {
  await load()
  await loadPareto()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb { margin-bottom: 16px; }
.empty { color: var(--el-text-color-secondary); font-size: 13px; }
.empty-inline { color: var(--el-text-color-secondary); }
.sub { font-size: 12px; color: var(--el-text-color-secondary); }
.pareto { display: flex; flex-direction: column; gap: 6px; }
.row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.idx { width: 18px; color: var(--el-text-color-secondary); }
.name { width: 110px; }
.bar-wrap { flex: 1; height: 14px; background: var(--el-fill-color-light); border-radius: 7px; overflow: hidden; }
.bar { height: 100%; background: var(--el-color-primary); border-radius: 7px; transition: width .3s; }
.qty { width: 50px; text-align: right; }
.pct { width: 60px; text-align: right; font-weight: 600; }
.cum { width: 100px; color: var(--el-text-color-secondary); }
</style>
