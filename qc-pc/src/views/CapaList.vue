<template>
  <el-card>
    <div class="filters">
      <el-input
        v-model="filter.keyword"
        placeholder="措施号 / 标题 / 责任人 / 来源单号"
        clearable
        style="width: 240px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
        <el-option label="待分析" value="OPEN" />
        <el-option label="措施执行中" value="DOING" />
        <el-option label="待验证" value="VERIFYING" />
        <el-option label="已关闭" value="CLOSED" />
        <el-option label="已撤销" value="CANCELLED" />
      </el-select>
      <el-checkbox v-model="filter.overdue" @change="reload">只看超期</el-checkbox>
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate()">新建措施</el-button>
    </div>

    <div class="bar">
      <span class="count">
        共 {{ total }} 条措施
        <template v-if="overdueCount > 0">
          ，其中 <b class="danger">{{ overdueCount }}</b> 条已超期
        </template>
        <template v-if="verifyingCount > 0">
          ，<b class="warn">{{ verifyingCount }}</b> 条等着验证
        </template>
      </span>
    </div>

    <!-- 该立却还没立的。这一块是「动作层」真正缺的那半截：
         /recurrence 要人先想到「我要立措施」才会被查一次，而实际情况是压根想不起来。
         帕累托每月第一根柱子都是同一个毛病，说明报表看得见、但没人被叫去做事 -->
    <el-collapse v-if="suggestions.length" v-model="suggestOpen" class="suggest">
      <el-collapse-item name="s">
        <template #title>
          <span class="sug-head">
            <b class="warn">{{ suggestions.length }}</b>
            个毛病反复出现、还没有措施——点开看该不该立
          </span>
        </template>
        <div v-for="s in suggestions" :key="s.productId + '|' + s.defectKey" class="sug">
          <div class="sug-main">
            <div class="sug-title">
              <span>{{ s.title }}</span>
              <el-tag v-if="s.regressed" type="danger" size="small" effect="plain">改过又犯</el-tag>
            </div>
            <div class="sug-note">{{ s.note }}</div>
            <div class="sub">{{ s.problem }}</div>
          </div>
          <el-button size="small" type="primary" plain @click="fromSuggestion(s)">立措施</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>

    <!-- 这张表解决的是帕累托管不到的那半截：柱子指出来之后，有没有人去改、改完有没有用 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="措施必须经过验证才能关闭"
      description="填完根因和措施、提交验证后，由没参与这条措施的人判有没有用。判无效就退回继续改——措施不够时退回去，比硬关掉、下个月再犯一遍便宜得多。"
      style="margin-bottom: 14px"
    />

    <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
      <el-table-column prop="capaNo" label="措施号" width="155">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.capaNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题 / 产品" width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <div>{{ row.title }}</div>
          <div class="sub">{{ row.productName || '未关联产品' }}<template v-if="row.defectCode"> · {{ row.defectCode }}</template></div>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="责任人" width="90" />
      <el-table-column label="要求完成" width="115">
        <template #default="{ row }">
          <div :class="{ danger: row.overdue }">{{ row.dueDate || '未定期限' }}</div>
          <div v-if="row.overdue" class="sub danger">超期 {{ row.overdueDays }} 天</div>
          <div v-else-if="row.daysOpen != null" class="sub">已开 {{ row.daysOpen }} 天</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <!-- 验证结论、撤销理由挂状态上 hover：列表不加备注列，但「为什么被退回」不能丢 -->
          <el-tooltip :content="row.verifyNote || row.cancelReason || row.statusText"
                      placement="top" :disabled="!row.verifyNote && !row.cancelReason">
            <el-tag size="small" :type="tagType(row.status)">{{ row.statusText }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="130">
        <template #default="{ row }">
          <span v-if="row.sourceRef">{{ row.sourceRef }}</span>
          <span v-else class="sub">{{ sourceText(row.sourceType) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'OPEN'">
            <el-button size="small" @click="start(row)">开始处理</el-button>
            <el-button size="small" @click="openAnalysis(row)">填措施</el-button>
            <el-button v-if="canReview" size="small" type="danger" plain @click="cancel(row)">撤销</el-button>
          </template>
          <template v-else-if="row.status === 'DOING'">
            <el-button size="small" @click="openAnalysis(row)">填措施</el-button>
            <el-button size="small" type="primary" @click="submitVerify(row)">提交验证</el-button>
            <el-button v-if="canReview" size="small" type="danger" plain @click="cancel(row)">撤销</el-button>
          </template>
          <template v-else-if="row.status === 'VERIFYING'">
            <!-- 责任人不能验自己：按钮直接不给，而不是点了再报错 -->
            <el-tooltip v-if="row.owner === myName" content="这条是你自己的措施，得由别人来验" placement="top">
              <el-button size="small" disabled>等别人验证</el-button>
            </el-tooltip>
            <el-button v-else-if="canReview" size="small" type="primary" @click="openVerify(row)">验证</el-button>
            <span v-else class="sub">等审核员验证</span>
            <!-- 提交后才发现搞错了（问题本身不成立）也得能撤，不能只等验证人判「无效」 -->
            <el-button v-if="canReview" size="small" type="danger" plain @click="cancel(row)">撤销</el-button>
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

  <!-- ---------------- 新建 ---------------- -->
  <el-dialog v-model="createDlg.visible" title="新建纠正预防措施" width="560px" top="6vh">
    <!-- 内容比一屏高的时候，滚的必须是表单区而不是整个弹窗——
         否则「建立」按钮被推到视口外面，1280×700 的笔记本上根本够不着 -->
    <el-form label-width="100px" class="dlg-form">
      <el-form-item label="标题" required>
        <el-input v-model="createDlg.title" maxlength="200" show-word-limit
                  placeholder="一句话说清是什么毛病，如「法兰外径连续 3 批超上限」" />
      </el-form-item>
      <el-form-item label="问题描述" required>
        <el-input v-model="createDlg.problem" type="textarea" :rows="3" maxlength="1000"
                  placeholder="什么产品、什么不良、影响多少件、什么时候开始" />
      </el-form-item>
      <el-form-item label="产品">
        <el-select v-model="createDlg.productId" placeholder="选择产品（用于再犯检测）" clearable filterable style="width: 100%">
          <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="不良">
        <el-select v-model="createDlg.defectCode" placeholder="选择不良代码（可不选）" clearable filterable style="width: 100%">
          <el-option v-for="d in defectCodes" :key="d.code" :label="`${d.code} ${d.name}`" :value="d.code" />
        </el-select>
      </el-form-item>

      <!-- 以前犯过几次：决定这次要不要真去查根因 -->
      <el-form-item v-if="recur.advice" label="以前">
        <div class="recur" :class="{ danger: recur.hasClosed }">{{ recur.advice }}</div>
      </el-form-item>

      <el-form-item label="责任" required>
        <el-input v-model="createDlg.owner" placeholder="谁负责跟这件事" style="width: 200px" />
      </el-form-item>
      <el-form-item label="要求完成">
        <el-date-picker v-model="createDlg.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" />
        <span class="sub ml">默认两周</span>
      </el-form-item>
      <el-form-item label="严重程度">
        <el-radio-group v-model="createDlg.severity">
          <el-radio-button label="CRITICAL">严重</el-radio-button>
          <el-radio-button label="MAJOR">一般</el-radio-button>
          <el-radio-button label="MINOR">轻微</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="根因 / 措施">
        <div class="sub">这时候通常还没有，先空着。查清楚再回来填——「员工质量意识不足」这种话不算根因。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createDlg.visible = false">取消</el-button>
      <el-button type="primary" :loading="createDlg.saving" @click="doCreate">建立</el-button>
    </template>
  </el-dialog>

  <!-- ---------------- 填根因与措施 ---------------- -->
  <el-dialog v-model="analysisDlg.visible" title="根因与措施" width="620px" top="8vh">
    <el-form label-width="96px" class="dlg-form">
      <el-form-item label="措施号">
        <span>{{ analysisDlg.row?.capaNo }}</span>
        <span class="sub ml">{{ analysisDlg.row?.title }}</span>
      </el-form-item>
      <el-form-item label="根本原因" required>
        <el-input v-model="analysisDlg.rootCause" type="textarea" :rows="3" maxlength="2000"
                  placeholder="为什么会发生？说清「什么条件下才出现」，而不是「操作工不小心」" />
      </el-form-item>
      <el-form-item label="纠正措施" required>
        <el-input v-model="analysisDlg.actionPlan" type="textarea" :rows="3" maxlength="2000"
                  placeholder="针对这个根因，具体做了什么。要能被别人复核：改了什么、换成什么" />
      </el-form-item>
      <el-form-item label="预防措施">
        <el-input v-model="analysisDlg.preventPlan" type="textarea" :rows="2" maxlength="2000"
                  placeholder="同类问题在别的产品、别的机台上会不会也有？有的话一起处理。不填也能提交验证" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="analysisDlg.visible = false">取消</el-button>
      <el-button type="primary" :loading="analysisDlg.saving" @click="doAnalysis">保存</el-button>
    </template>
  </el-dialog>

  <!-- ---------------- 验证 ---------------- -->
  <el-dialog v-model="verifyDlg.visible" title="验证措施有没有用" width="560px" top="8vh">
    <el-form label-width="96px" class="dlg-form">
      <el-form-item label="措施号">
        <span>{{ verifyDlg.row?.capaNo }}</span>
        <span class="sub ml">{{ verifyDlg.row?.title }}</span>
      </el-form-item>
      <el-form-item label="根因">
        <div class="quote">{{ verifyDlg.row?.rootCause || '未填写' }}</div>
      </el-form-item>
      <el-form-item label="措施">
        <div class="quote">{{ verifyDlg.row?.actionPlan || '未填写' }}</div>
      </el-form-item>
      <el-form-item label="判定" required>
        <el-radio-group v-model="verifyDlg.effective">
          <el-radio :label="true">有效，关闭</el-radio>
          <el-radio :label="false">无效，退回继续改</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="验证结论" required>
        <el-input v-model="verifyDlg.note" type="textarea" :rows="3" maxlength="1000"
                  placeholder="看了什么数据、连续几个批次没再出现。「已改善」这种话三个月后没人知道当时看了什么" />
      </el-form-item>
      <el-form-item v-if="verifyDlg.effective === false" label="退回后">
        <div class="sub">措施会回到「措施执行中」，责任人会收到通知。改完可以再提交验证。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="verifyDlg.visible = false">取消</el-button>
      <el-button type="primary" :loading="verifyDlg.saving" @click="doVerify">提交判定</el-button>
    </template>
  </el-dialog>

  <!-- ---------------- 详情 ---------------- -->
  <el-drawer v-model="detail.visible" :title="detail.row?.capaNo || '措施详情'" size="520px">
    <div v-if="detail.row" class="detail">
      <div class="kv"><span>状态</span><el-tag size="small" :type="tagType(detail.row.status)">{{ detail.row.statusText }}</el-tag></div>
      <div class="kv"><span>标题</span><b>{{ detail.row.title }}</b></div>
      <div class="kv"><span>产品</span>{{ detail.row.productName || '—' }}<template v-if="detail.row.defectCode"> · {{ detail.row.defectCode }}</template></div>
      <div class="kv"><span>来源</span>{{ detail.row.sourceRef || sourceText(detail.row.sourceType) }}</div>
      <div class="kv"><span>责任人</span>{{ detail.row.owner }}</div>
      <div class="kv"><span>期限</span>
        <span :class="{ danger: detail.row.overdue }">{{ detail.row.dueDate || '未定期限' }}</span>
        <span v-if="detail.row.overdue" class="danger ml">超期 {{ detail.row.overdueDays }} 天</span>
      </div>
      <div class="kv"><span>建立</span>{{ detail.row.createdBy }} · {{ fmt(detail.row.createdAt) }}</div>

      <el-alert v-if="detail.row.recurrenceNote" type="warning" :closable="false" show-icon
                title="这个毛病以前出现过" :description="detail.row.recurrenceNote"
                style="margin: 12px 0" />

      <h4>问题</h4>
      <div class="block">{{ detail.row.problem }}</div>

      <h4>根本原因</h4>
      <div class="block" :class="{ empty: !detail.row.rootCause }">{{ detail.row.rootCause || '还没写' }}</div>

      <h4>纠正措施</h4>
      <div class="block" :class="{ empty: !detail.row.actionPlan }">{{ detail.row.actionPlan || '还没写' }}</div>

      <h4>预防措施</h4>
      <div class="block" :class="{ empty: !detail.row.preventPlan }">{{ detail.row.preventPlan || '没写（不影响关闭）' }}</div>

      <template v-if="detail.row.verifyNote">
        <h4>验证结论 · {{ detail.row.verifiedBy }} · {{ fmt(detail.row.verifiedAt) }}</h4>
        <div class="block" :class="detail.row.closeEffective ? 'ok' : 'danger'">
          {{ detail.row.closeEffective ? '有效' : '无效，已退回' }}：{{ detail.row.verifyNote }}
        </div>
      </template>

      <template v-if="detail.row.cancelReason">
        <h4>撤销理由</h4>
        <div class="block danger">{{ detail.row.cancelReason }}</div>
      </template>

      <template v-if="detail.row.remark">
        <h4>备注</h4>
        <div class="block sub">{{ detail.row.remark }}</div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getStoredUser } from '../api/http'

const route = useRoute()
const user = getStoredUser() || {}
const canReview = computed(() => user.role === 'ADMIN' || user.role === 'REVIEWER')
// 和后端 LoginUser.displayName() 同口径：有真实姓名用姓名，否则用账号
const myName = computed(() => user.realName || user.username || '')

const rows = ref([])
const total = ref(0)
const overdueCount = ref(0)
const verifyingCount = ref(0)
const loading = ref(false)
const page = reactive({ current: 1, size: 10 })
const filter = reactive({ keyword: '', status: '', overdue: false })

const products = ref([])
const defectCodes = ref([])

// 「该立却没立」的建议。判断全在后端 CapaRules.advice 一处，
// 这里只管显示——前端自己再判一次门槛，早晚会出现「页面推了、点进去又说不用立」
const suggestions = ref([])
const suggestOpen = ref([])

async function loadSuggestions() {
  try {
    suggestions.value = (await http.get('/capa/suggestions')) || []
  } catch {
    suggestions.value = []
  }
}

/**
 * 从建议直接建单：标题与问题描述用后端拼好的，不再自己拼一遍。
 * 带上 productId，弹窗里的「以前」才查得出来——帕累托那条路是跨产品的，带不上，
 * 只能看到历史次数而看不到上一次关过的那条措施。
 */
function fromSuggestion(s) {
  openCreate({
    productId: s.productId,
    defectCode: s.defectKey,
    title: s.title,
    problem: s.problem
  })
}

function queryParams() {
  return {
    keyword: filter.keyword.trim() || undefined,
    status: filter.status || undefined,
    overdue: filter.overdue ? true : undefined
  }
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/capa/page', {
      params: { current: page.current, size: page.size, ...queryParams() }
    })
    rows.value = data.records || []
    total.value = data.total || 0
    await refreshCounts()
  } finally {
    loading.value = false
  }
}

/**
 * 超期与待验证两个数字用轻量查询（size=1，只看 total）。
 * 不做专门的统计接口：多一个接口就多一处口径，而这两个数和列表用的是同一套筛选。
 */
async function refreshCounts() {
  try {
    const [od, vf] = await Promise.all([
      http.get('/capa/page', { params: { current: 1, size: 1, overdue: true } }),
      http.get('/capa/page', { params: { current: 1, size: 1, status: 'VERIFYING' } })
    ])
    overdueCount.value = od.total || 0
    verifyingCount.value = vf.total || 0
  } catch {
    overdueCount.value = 0
    verifyingCount.value = 0
  }
}

function reload() {
  page.current = 1
  load()
}

function resetFilters() {
  Object.assign(filter, { keyword: '', status: '', overdue: false })
  reload()
}

function rowClass({ row }) {
  return row.overdue ? 'overdue-row' : ''
}

function tagType(status) {
  return {
    OPEN: 'info',
    DOING: 'primary',
    VERIFYING: 'warning',
    CLOSED: 'success',
    CANCELLED: 'info'
  }[status] || 'info'
}

function sourceText(type) {
  return { DEFECT: '不良记录', COMPLAINT: '客诉', AUDIT: '体系审核', OTHER: '手工创建' }[type] || '手工创建'
}

function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

/* ---------------- 新建 ---------------- */

const createDlg = reactive({
  visible: false,
  sourceType: 'OTHER',
  sourceId: null,
  sourceRef: '',
  title: '',
  problem: '',
  productId: null,
  defectCode: '',
  owner: '',
  dueDate: '',
  severity: 'MINOR',
  saving: false
})

const recur = reactive({ advice: '', hasClosed: false })

function openCreate(preset) {
  Object.assign(createDlg, {
    visible: true,
    sourceType: preset?.sourceType || 'OTHER',
    sourceId: preset?.sourceId || null,
    sourceRef: preset?.sourceRef || '',
    title: preset?.title || '',
    problem: preset?.problem || '',
    productId: preset?.productId || null,
    defectCode: preset?.defectCode || '',
    owner: preset?.owner || '',
    dueDate: defaultDue(),
    severity: preset?.severity || 'MINOR',
    saving: false
  })
  Object.assign(recur, { advice: '', hasClosed: false })
  loadRecurrence()
}

/** 默认两周：一周常常不够查根因，一个月又容易拖成「下个月再说」 */
function defaultDue() {
  const d = new Date()
  d.setDate(d.getDate() + 14)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function loadRecurrence() {
  if (!createDlg.productId || !createDlg.defectCode) {
    Object.assign(recur, { advice: '', hasClosed: false })
    return
  }
  try {
    const r = await http.get('/capa/recurrence', {
      params: { productId: createDlg.productId, defectKey: createDlg.defectCode }
    })
    recur.advice = r.advice || ''
    recur.hasClosed = (r.closedCapas || []).length > 0
  } catch {
    Object.assign(recur, { advice: '', hasClosed: false })
  }
}

watch(() => [createDlg.productId, createDlg.defectCode], loadRecurrence)

async function doCreate() {
  if (!createDlg.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  if (!createDlg.problem.trim()) {
    ElMessage.warning('问题描述不能为空')
    return
  }
  if (!createDlg.owner.trim()) {
    ElMessage.warning('要指定责任人，没人负责的措施不会有人做')
    return
  }
  createDlg.saving = true
  try {
    const res = await http.post('/capa', {
      sourceType: createDlg.sourceType,
      sourceId: createDlg.sourceId || undefined,
      sourceRef: createDlg.sourceRef || undefined,
      title: createDlg.title.trim(),
      problem: createDlg.problem.trim(),
      productId: createDlg.productId || undefined,
      defectCode: createDlg.defectCode || undefined,
      severity: createDlg.severity,
      owner: createDlg.owner.trim(),
      dueDate: createDlg.dueDate || undefined
    })
    ElMessage.success(`已建立 ${res.capaNo}`)
    createDlg.visible = false
    // 建完这条就不再是「该立没立」了，建议列表得跟着变——不然人刚建完，
    // 上面还挂着「1 个毛病反复出现」，会以为没建上
    await Promise.all([load(), loadSuggestions()])
  } finally {
    createDlg.saving = false
  }
}

/* ---------------- 开始 ---------------- */

async function start(row) {
  await http.post(`/capa/${row.id}/start`)
  ElMessage.success('已开始处理')
  await load()
}

/* ---------------- 填根因与措施 ---------------- */

const analysisDlg = reactive({
  visible: false,
  row: null,
  rootCause: '',
  actionPlan: '',
  preventPlan: '',
  saving: false
})

function openAnalysis(row) {
  Object.assign(analysisDlg, {
    visible: true,
    row,
    rootCause: row.rootCause || '',
    actionPlan: row.actionPlan || '',
    preventPlan: row.preventPlan || '',
    saving: false
  })
}

async function doAnalysis() {
  analysisDlg.saving = true
  try {
    await http.post(`/capa/${analysisDlg.row.id}/analysis`, {
      rootCause: analysisDlg.rootCause.trim() || undefined,
      actionPlan: analysisDlg.actionPlan.trim() || undefined,
      preventPlan: analysisDlg.preventPlan.trim() || undefined
    })
    ElMessage.success('已保存')
    analysisDlg.visible = false
    await load()
  } finally {
    analysisDlg.saving = false
  }
}

/* ---------------- 提交验证 ---------------- */

async function submitVerify(row) {
  if (!row.rootCause || !row.actionPlan) {
    ElMessage.warning('根因和纠正措施都填了才能提交验证——没有根因的措施只是把这批坏件处理掉了')
    return
  }
  try {
    await ElMessageBox.confirm(
      '提交后根因和措施就冻结了，验证人看到的内容必须和你提交的一致。确认提交？',
      '提交验证',
      { confirmButtonText: '提交', cancelButtonText: '再看看', type: 'info' }
    )
  } catch {
    return
  }
  await http.post(`/capa/${row.id}/submit-verify`)
  ElMessage.success('已提交验证')
  await load()
}

/* ---------------- 验证 ---------------- */

const verifyDlg = reactive({ visible: false, row: null, effective: true, note: '', saving: false })

function openVerify(row) {
  Object.assign(verifyDlg, {
    visible: true,
    row,
    effective: true,
    note: '',
    saving: false
  })
}

async function doVerify() {
  if (!verifyDlg.note.trim()) {
    ElMessage.warning('验证结论不能为空：看了什么数据、连续几批没再出现')
    return
  }
  verifyDlg.saving = true
  try {
    await http.post(`/capa/${verifyDlg.row.id}/verify`, {
      effective: verifyDlg.effective,
      note: verifyDlg.note.trim()
    })
    ElMessage.success(verifyDlg.effective ? '已判有效并关闭' : '已判无效，退回继续改')
    verifyDlg.visible = false
    await load()
  } finally {
    verifyDlg.saving = false
  }
}

/* ---------------- 撤销 ---------------- */

async function cancel(row) {
  let reason
  try {
    const { value } = await ElMessageBox.prompt(
      '撤销要写理由：是问题本身搞错了，还是并入了别的措施？',
      `撤销 ${row.capaNo}`,
      {
        inputType: 'textarea',
        inputPlaceholder: '如：并入 CAPA20261111001，同一次模具问题',
        inputValidator: v => (v && v.trim().length >= 2 ? true : '理由至少写两个字'),
        confirmButtonText: '确认撤销',
        cancelButtonText: '返回'
      }
    )
    reason = value
  } catch {
    return
  }
  await http.post(`/capa/${row.id}/cancel`, { reason: reason.trim() })
  ElMessage.success('已撤销')
  await load()
}

/* ---------------- 详情 ---------------- */

const detail = reactive({ visible: false, row: null })

async function openDetail(row) {
  detail.row = await http.get(`/capa/${row.id}`)
  detail.visible = true
}

onMounted(async () => {
  const [p, d] = await Promise.all([
    http.get('/products').catch(() => []),
    http.get('/defect-codes').catch(() => [])
  ])
  products.value = p || []
  defectCodes.value = d || []

  // 从不良列表点「建措施」过来：带着来源，产品与不良标识由后端按来源补
  if (route.query.defectId) {
    openCreate({
      sourceType: 'DEFECT',
      sourceId: Number(route.query.defectId),
      sourceRef: route.query.inspectionNo ? String(route.query.inspectionNo) : '',
      productId: route.query.productId ? Number(route.query.productId) : null,
      defectCode: route.query.defectCode ? String(route.query.defectCode) : '',
      title: route.query.title ? String(route.query.title) : '',
      problem: route.query.problem ? String(route.query.problem) : ''
    })
  } else if (route.query.defectCode) {
    // 从帕累托那一行的「立措施」过来：只有不良和一段现成的问题描述，
    // 产品和责任人都得人自己选——帕累托是跨产品的，替人挑产品只会挑错
    openCreate({
      defectCode: String(route.query.defectCode),
      title: route.query.title ? String(route.query.title) : '',
      problem: route.query.problem ? String(route.query.problem) : ''
    })
  }
  load()
  loadSuggestions()
})
</script>

<style scoped>
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 14px;
           padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.bar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.count { font-size: 13px; color: var(--el-text-color-secondary); }
.spacer { flex: 1; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.dlg-form { max-height: 62vh; overflow-y: auto; padding-right: 8px; }
.sub { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.ml { margin-left: 10px; }
.danger { color: var(--el-color-danger); }
.warn { color: var(--el-color-warning); }
.ok { color: var(--el-color-success); }
.recur { font-size: 13px; line-height: 1.6; padding: 8px 10px; border-radius: 4px;
         background: var(--el-color-info-light-9); }
.recur.danger { background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); }
.quote { font-size: 13px; line-height: 1.6; color: var(--el-text-color-regular);
         white-space: pre-wrap; max-height: 90px; overflow: auto; }
.detail h4 { margin: 18px 0 6px; font-size: 13px; color: var(--el-text-color-regular); }
.kv { display: flex; gap: 12px; align-items: baseline; font-size: 13px; padding: 4px 0; }
.kv > span:first-child { width: 56px; color: var(--el-text-color-secondary); flex: none; }
.block { font-size: 13px; line-height: 1.7; white-space: pre-wrap;
         background: var(--el-fill-color-lighter); padding: 10px 12px; border-radius: 4px; }
.block.empty { color: var(--el-text-color-placeholder); }
:deep(.overdue-row) { background: var(--el-color-danger-light-9); }
.suggest { margin-bottom: 12px; border-top: none; }
:deep(.suggest .el-collapse-item__header) { height: auto; min-height: 40px; line-height: 1.5;
                                            padding: 6px 0; border-bottom: none; }
:deep(.suggest .el-collapse-item__wrap) { border-bottom: none; }
.sug-head { font-size: 13px; }
.sug { display: flex; gap: 12px; align-items: flex-start; padding: 10px 0;
       border-top: 1px dashed var(--el-border-color-lighter); }
.sug:first-child { border-top: none; padding-top: 0; }
.sug-main { flex: 1; min-width: 0; }
.sug-title { display: flex; align-items: center; gap: 8px; font-size: 13px;
             color: var(--el-text-color-primary); }
.sug-note { font-size: 12px; line-height: 1.65; margin-top: 4px;
            color: var(--el-color-warning-dark-2); }
</style>
