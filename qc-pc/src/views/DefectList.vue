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

      <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
        <el-table-column label="检验单" width="130">
          <template #default="{ row }">#{{ row.inspectionId }} / 第 {{ row.pieceNo }} 件</template>
        </el-table-column>
        <el-table-column prop="defectName" label="不良" min-width="110" />
        <el-table-column prop="defectCode" label="代码" width="100" />
        <el-table-column prop="defectQty" label="数量" width="70" />
        <!-- 处置和状态本来各占一列，加起来 220px。加了返修票列之后整张表挤到
             把「复检单」推到右侧固定的操作列底下，这页是车间天天点的，宁可合起来 -->
        <el-table-column label="处置 / 状态" width="120">
          <template #default="{ row }">
            <div>{{ dispositionText(row.disposition) }}</div>
            <div class="sub">
              <el-tag size="small" :type="statusType(row.dispositionStatus)">
                {{ statusText(row.dispositionStatus) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="返修票" width="140">
          <template #default="{ row }">
            <!-- 有票就显示票号和状态：不良列表上看不出「这件送去修了没有」，
                 是车间最常见的一句追问 -->
            <template v-if="row.reworkNo">
              <el-link type="primary" @click="goRework(row.reworkNo)">{{ row.reworkNo }}</el-link>
              <div class="sub">
                <el-tag size="small" :type="reworkTagType(row.reworkStatus)">
                  {{ reworkText(row.reworkStatus) }}
                </el-tag>
              </div>
            </template>
            <el-button
              v-else-if="row.disposition === 'REWORK' && isOpen(row)"
              size="small"
              text
              type="primary"
              @click="openReworkTicket(row)"
            >
              开票
            </el-button>
            <span v-else class="empty-inline">—</span>
          </template>
        </el-table-column>
        <el-table-column label="复检单" width="145">
          <template #default="{ row }">
            <template v-if="row.recheckInspectionNo">
              <el-link type="primary" @click="goInspection(row.recheckInspectionId)">
                {{ row.recheckInspectionNo }}
              </el-link>
              <div class="sub">
                <el-tag v-if="row.recheckStatus === 'DRAFT'" size="small" type="info">未提交</el-tag>
                <el-tag v-else-if="row.recheckResult === 'PASS'" size="small" type="success">复检合格</el-tag>
                <el-tag v-else-if="row.recheckResult === 'FAIL'" size="small" type="danger">复检不合格</el-tag>
                <el-tag v-else-if="row.recheckStatus === 'VOID'" size="small" type="info">已作废</el-tag>
                <el-tag v-else size="small" type="warning">{{ row.recheckStatus }}</el-tag>
              </div>
            </template>
            <span v-else class="empty-inline">—</span>
          </template>
        </el-table-column>
        <el-table-column label="处置人 / 理由" min-width="150">
          <template #default="{ row }">
            <div>{{ row.dispositionBy || '—' }}</div>
            <div
              class="sub reason"
              :title="row.concessionReason || row.dispositionNote || ''"
            >{{ row.concessionReason || row.dispositionNote || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <!-- 还没定处置方式 -->
            <template v-if="!row.disposition">
              <el-button size="small" @click="dispose(row, 'REWORK', 'PROCESSING')">返工</el-button>
              <el-button size="small" @click="dispose(row, 'RETURN', 'PROCESSING')">退货</el-button>
              <el-button size="small" type="warning" @click="dispose(row, 'SCRAP', 'DONE')">报废</el-button>
              <el-button size="small" type="danger" @click="openConcession(row)">让步接收</el-button>
            </template>

            <!-- 返工待开始：缺陷判出来时处置就预填成「返工」了，但那只是建议 -->
            <template v-else-if="row.disposition === 'REWORK' && row.dispositionStatus === 'PENDING'">
              <el-button size="small" type="primary" @click="dispose(row, 'REWORK', 'PROCESSING')">开始返工</el-button>
              <el-button size="small" type="warning" @click="dispose(row, 'SCRAP', 'DONE')">改报废</el-button>
              <el-button size="small" @click="dispose(row, 'RETURN', 'PROCESSING')">改退货</el-button>
              <el-button size="small" type="danger" @click="openConcession(row)">让步接收</el-button>
            </template>

            <!-- 返工中：开复检 → 填报提交 → 回来标完成 -->
            <template v-else-if="row.disposition === 'REWORK' && isOpen(row)">
              <el-button v-if="!row.recheckInspectionNo" size="small" type="primary" @click="openRecheck(row)">
                开复检单
              </el-button>
              <el-button v-else size="small" type="primary" plain @click="goInspection(row.recheckInspectionId)">
                去复检
              </el-button>
              <el-button size="small" type="success" @click="finishRework(row)">完成返工</el-button>
              <el-button size="small" type="warning" @click="dispose(row, 'SCRAP', 'DONE')">改报废</el-button>
            </template>

            <!-- 退货中 -->
            <template v-else-if="row.disposition === 'RETURN' && isOpen(row)">
              <el-button size="small" type="success" @click="dispose(row, 'RETURN', 'DONE')">完成退货</el-button>
              <el-button size="small" type="warning" @click="dispose(row, 'SCRAP', 'DONE')">改报废</el-button>
            </template>

            <template v-else-if="isOpen(row)">
              <el-button size="small" type="success" @click="dispose(row, row.disposition, 'DONE')">
                标为完成
              </el-button>
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

    <!-- 开返修票：这张票跟的是「件」，不是结论 -->
    <el-dialog v-model="rwVisible" title="开返修票" width="500px">
      <el-alert
        type="info"
        :closable="false"
        class="mb"
        title="返修票记的是件：送修几件、谁在修、说好什么时候回来"
        description="返修完成时报「返好 / 返废」，返好几件就自动开几件的复检单。票上的数量必须是真的送去修的数量。"
      />
      <el-form label-width="90px">
        <el-form-item label="不良">
          <span>{{ rwTarget?.defectName }}</span>
          <span class="sub" style="margin-left: 8px">判出 {{ rwTarget?.defectQty }} 件</span>
        </el-form-item>
        <el-form-item label="送修数量">
          <el-input-number v-model="rwForm.qty" :min="1" :max="200" style="width: 160px" />
          <span class="sub" style="margin-left: 10px">实际送去返修的件数，可能少于判出的件数</span>
        </el-form-item>
        <el-form-item label="返修人">
          <el-input v-model="rwForm.assignee" maxlength="64" placeholder="人名或工位，如「三号返修台 老李」" />
        </el-form-item>
        <el-form-item label="要求完成">
          <el-date-picker v-model="rwForm.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="返修要求">
          <el-input
            v-model="rwForm.requirement"
            type="textarea"
            :rows="2"
            maxlength="200"
            placeholder="怎么修、修到什么程度。空着的话返修工只能凭经验猜"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rwVisible = false">取消</el-button>
        <el-button type="primary" :loading="rwSaving" @click="doCreateRework">开票</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { checkQty } from '../utils/rework'

const router = useRouter()
const route = useRoute()
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
  // 操作人一律以登录态为准（后端 UserContext 优先取 token），前端不再传这个名字
  if (disposition === 'REWORK' && status === 'PROCESSING') {
    const { value } = await ElMessageBox.prompt('返工说明（可留空）', '开始返工', { inputPlaceholder: '返工工序 / 负责人' })
    await http.post(`/defects/${row.id}/dispose`, { disposition, status, note: value })
  } else {
    await http.post(`/defects/${row.id}/dispose`, { disposition, status })
  }
  ElMessage.success('已提交处置')
  await load()
}

/**
 * 开复检单。
 *
 * 件数不是用户填的，而是这条不良的返工件数——返工了 3 件就验 3 件。
 * 也不给用户改允收数：复检是返工有效性确认，不是抽样验收，Ac 恒为 0。
 */
async function openRecheck(row) {
  const qty = row.defectQty && row.defectQty > 0 ? row.defectQty : 1
  try {
    await ElMessageBox.confirm(
      `将按返工 ${qty} 件建复检单：逐件全检、Ac=0，判据沿用原单的标准快照。` +
      `复检不合格的，返工不能标完成。`,
      '开复检单',
      { confirmButtonText: '开单并去填报', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const r = await http.post(`/defects/${row.id}/recheck/open`)
  ElMessage.success(`已建复检单 ${r.inspectionNo}，共 ${r.sampleQty} 件`)
  router.push(`/inspection/${r.inspectionId}`)
}

async function finishRework(row) {
  try {
    await ElMessageBox.confirm(
      '确认返工已完成？系统会核对复检单：没提交、或判定不合格的，不允许标完成。',
      '完成返工',
      { confirmButtonText: '确认完成', cancelButtonText: '再等等' }
    )
  } catch {
    return
  }
  await http.post(`/defects/${row.id}/dispose`, { disposition: 'REWORK', status: 'DONE' })
  ElMessage.success('返工已闭环')
  await load()
}

function isOpen(row) {
  return row.dispositionStatus === 'PENDING' || row.dispositionStatus === 'PROCESSING'
}

/* ---------------- 返修票 ---------------- */

const rwVisible = ref(false)
const rwSaving = ref(false)
const rwTarget = ref(null)
const rwForm = reactive({ qty: 1, assignee: '', dueDate: '', requirement: '' })

function reworkText(s) {
  return { OPEN: '待返修', DOING: '返修中', DONE: '已完工', CANCELLED: '已取消' }[s] || s
}

function reworkTagType(s) {
  return { OPEN: 'warning', DOING: 'primary', DONE: 'success', CANCELLED: 'info' }[s] || 'info'
}

function goRework(no) {
  router.push({ path: '/rework', query: { keyword: no } })
}

/** 三天后，按本地时区算（toISOString 是 UTC，早上八点前会差一天） */
function defaultDue() {
  const d = new Date()
  d.setDate(d.getDate() + 3)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function openReworkTicket(row) {
  rwTarget.value = row
  Object.assign(rwForm, {
    // 默认按判出的件数送修：多数情况是全部送修，改少比改多更容易被漏掉
    qty: row.defectQty && row.defectQty > 0 ? row.defectQty : 1,
    assignee: '',
    dueDate: defaultDue(),
    requirement: ''
  })
  rwVisible.value = true
}

async function doCreateRework() {
  const err = checkQty(rwForm.qty)
  if (err) {
    ElMessage.warning(err)
    return
  }
  rwSaving.value = true
  try {
    const r = await http.post('/rework', {
      defectId: rwTarget.value.id,
      qty: rwForm.qty,
      assignee: rwForm.assignee.trim() || undefined,
      dueDate: rwForm.dueDate || undefined,
      requirement: rwForm.requirement.trim() || undefined
    })
    rwVisible.value = false
    ElMessage.success(`已开返修票 ${r.reworkNo}，共 ${r.qty} 件`)
    await load()
  } finally {
    rwSaving.value = false
  }
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

/**
 * 工作台点「去处置」会带 ?focus=<id> 过来。
 * 列表本来就是全量加载，直接把那一行标出来即可，不必再写一个详情页。
 */
const focusId = ref(route.query.focus || '')

function rowClass({ row }) {
  return String(row.id) === String(focusId.value) ? 'focus-row' : ''
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
/* 处置理由有时是一整句话（报废原因、返废说明）。不裁的话一行能撑到五行高，
   整张表变成「一行数据一格字」，其他行反而不好扫。原文留在 tooltip 里 */
.reason {
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.pareto { display: flex; flex-direction: column; gap: 6px; }
.row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.idx { width: 18px; color: var(--el-text-color-secondary); }
.name { width: 110px; }
.bar-wrap { flex: 1; height: 14px; background: var(--el-fill-color-light); border-radius: 7px; overflow: hidden; }
.bar { height: 100%; background: var(--el-color-primary); border-radius: 7px; transition: width .3s; }
.qty { width: 50px; text-align: right; }
.pct { width: 60px; text-align: right; font-weight: 600; }
.cum { width: 100px; color: var(--el-text-color-secondary); }
:deep(.focus-row) { background: var(--el-color-warning-light-9); }
</style>
