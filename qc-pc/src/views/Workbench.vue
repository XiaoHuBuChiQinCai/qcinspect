<template>
  <div class="wb">
    <!-- 谁的活、有多少 —— 这是打开系统第一个要回答的问题 -->
    <div class="hero">
      <div>
        <div class="hi">{{ greeting }}，{{ displayName }}</div>
        <div class="sub">
          {{ today }} · {{ roleText }}
          <template v-if="summary.actionable">
            · <b class="hl">{{ summary.actionable }}</b> 件事等你处理
          </template>
          <template v-else>· 手上的活都清完了</template>
        </div>
      </div>
      <div class="spacer" />
      <el-button :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <div class="stats">
      <div
        v-for="s in stats"
        :key="s.key"
        :class="['stat', s.cls, s.count ? '' : 'zero']"
        @click="scrollTo(s.sec)"
      >
        <div class="num">{{ s.count }}</div>
        <div class="label">{{ s.label }}</div>
        <div class="hint">{{ s.hint }}</div>
      </div>
    </div>

    <!-- 待检批次：建了批次却一直没开检验单。没检的东西不进合格率分母，
         报表上完全看不出来，只能在这里点名 -->
    <el-card v-if="batches.length" id="sec-batch" class="block">
      <template #header>
        <div class="head">
          <span>
            待检批次
            <el-tag v-if="batchUrgent" type="danger" size="small">{{ batchUrgent }}</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">都在正常节奏内</el-tag>
          </span>
          <div class="spacer" />
          <el-button link type="primary" @click="router.push('/batches')">去批次列表</el-button>
        </div>
      </template>
      <el-alert
        v-if="shippedNoInspection"
        type="error"
        :closable="false"
        class="mb"
        title="有批次已经出货，却一条有效的检验结论都没有。这是既成事实的违规，优先补检或走让步放行。"
      />
      <el-table :data="batches" size="small" v-loading="loading" empty-text="没有待检批次">
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="productName" label="产品" min-width="130" show-overflow-tooltip />
        <el-table-column label="检验类型" width="110">
          <template #default="{ row }">{{ typeText(row.inspectType) }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column prop="supplier" label="供应商" min-width="120" show-overflow-tooltip />
        <el-table-column label="到货日期" width="110">
          <template #default="{ row }">{{ row.arrivedDate || row.createdDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="停留" width="85">
          <template #default="{ row }">
            <span :class="row.urgent ? 'ng' : ''">{{ row.idleDays }} 天</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="115">
          <template #default="{ row }">
            <el-tag :type="batchLevelType(row.level)" size="small">{{ batchLevelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="260">
          <template #default="{ row }">
            <div v-for="(f, i) in row.flags" :key="i" class="flag">{{ f }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="105" fixed="right">
          <template #default>
            <el-button size="small" type="warning" plain @click="router.push('/batches')">去开单</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="batchUrgent > pageSize" class="more">
        共 {{ batchUrgent }} 个需要处理，先显示前 20 个，其余去「批次列表」按状态筛
      </div>
    </el-card>

    <!-- 待审核 -->
    <el-card v-if="canReview" id="sec-review" class="block">
      <template #header>
        <div class="head">
          <span>待我审核 <el-tag v-if="reviews.length" type="warning" size="small">{{ reviews.length }}</el-tag></span>
          <div class="spacer" />
          <el-button link type="primary" @click="router.push('/inspections')">去检验单列表</el-button>
        </div>
      </template>
      <el-table :data="reviews" size="small" v-loading="loading" empty-text="没有待审核的单据">
        <el-table-column prop="inspectionNo" label="检验单号" width="160" />
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="productName" label="产品" min-width="120" />
        <el-table-column label="判定" width="90">
          <template #default="{ row }">
            <el-tag :type="resultType(row.result)" size="small">{{ resultText(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="合格 / 不合格" width="130">
          <template #default="{ row }">
            <span class="ok">{{ row.passedQty }}</span>
            <span class="sep">/</span>
            <span :class="row.failedQty > 0 ? 'ng' : ''">{{ row.failedQty }}</span>
            <span class="sub2"> of {{ row.sampleQty }}</span>
          </template>
        </el-table-column>
        <el-table-column label="检验员" width="100">
          <template #default="{ row }">
            {{ row.inspectorName || '—' }}
            <el-tag v-if="isSelf(row)" size="small" type="warning" effect="plain">本人</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ fmt(row.inspectedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="openReview(row)">审核</el-button>
            <el-button size="small" @click="router.push(`/inspection/${row.id}`)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 未关闭客诉 -->
    <el-card id="sec-complaint" class="block">
      <template #header>
        <div class="head">
          <span>未关闭客诉 <el-tag v-if="complaints.length" type="danger" size="small">{{ complaints.length }}</el-tag></span>
          <div class="spacer" />
          <el-button link type="primary" @click="router.push('/complaints')">去客户反馈</el-button>
        </div>
      </template>
      <el-table :data="complaints" size="small" v-loading="loading" empty-text="没有未关闭的客户投诉">
        <el-table-column prop="complaintNo" label="客诉单号" width="150" />
        <el-table-column prop="customer" label="客户" min-width="130" />
        <el-table-column prop="batchNo" label="批次号" width="140" />
        <el-table-column label="严重度" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="severityType(row.severity)">{{ severityText(row.severity) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'warning'">
              {{ row.status === 'OPEN' ? '待处理' : '处理中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="客诉日期" width="110">
          <template #default="{ row }">{{ row.complaintDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="goComplaint(row)">去处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 待处置不良 -->
    <el-card id="sec-defect" class="block">
      <template #header>
        <div class="head">
          <span>待处置不良 <el-tag v-if="defects.length" type="danger" size="small">{{ defects.length }}</el-tag></span>
          <div class="spacer" />
          <el-button link type="primary" @click="router.push('/defects')">去不良处置</el-button>
        </div>
      </template>
      <el-table :data="pagedDefects" size="small" v-loading="loading" empty-text="没有待处置的不良">
        <el-table-column prop="inspectionNo" label="检验单号" width="160" />
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="pieceNo" label="件号" width="110" />
        <el-table-column prop="defectName" label="不良项" min-width="140" />
        <el-table-column prop="defectQty" label="数量" width="80" />
        <el-table-column label="当前处置" width="110">
          <template #default="{ row }">{{ dispositionText(row.disposition) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.dispositionStatus === 'PENDING' ? 'danger' : 'warning'" size="small">
              {{ row.dispositionStatus === 'PENDING' ? '待处理' : '处理中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提出时间" width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="goDispose(row)">去处置</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="defects.length > pageSize" class="more">
        共 {{ defects.length }} 条，先显示前 {{ pageSize }} 条，其余去「不良处置」页处理
      </div>
    </el-card>

    <!-- 超期量具 -->
    <el-card id="sec-gauge" class="block">
      <template #header>
        <div class="head">
          <span>超期未校准量具 <el-tag v-if="gauges.length" type="info" size="small">{{ gauges.length }}</el-tag></span>
          <div class="spacer" />
          <el-button link type="primary" @click="router.push('/gauges')">去量具台账</el-button>
        </div>
      </template>
      <el-alert
        v-if="gauges.length"
        type="warning"
        :closable="false"
        class="mb"
        title="超期量具量出来的数据，体系审核直接判无效。先校准，再开单。"
      />
      <el-table :data="gauges" size="small" v-loading="loading" empty-text="所有在用器具都在校准有效期内">
        <el-table-column prop="gaugeCode" label="器具编号" width="150" />
        <el-table-column prop="gaugeName" label="名称" min-width="140" />
        <el-table-column prop="ownerDept" label="使用部门" width="140" />
        <el-table-column label="上次校准" width="130">
          <template #default="{ row }">{{ row.calibrateDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="有效期至" width="130">
          <template #default="{ row }">{{ row.nextCalibrateDate }}</template>
        </el-table-column>
        <el-table-column label="超期" width="100">
          <template #default="{ row }">
            <span class="ng">{{ row.overdueDays }} 天</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default>
            <el-button size="small" type="warning" plain @click="router.push('/gauges')">去校准</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 我的草稿 / 被退回 -->
    <el-card id="sec-mine" class="block">
      <template #header>
        <div class="head">
          <span>
            我的草稿
            <el-tag v-if="summary.myDraft" size="small">{{ summary.myDraft }}</el-tag>
            <el-tag v-if="summary.myRejected" type="danger" size="small" class="ml">
              被退回 {{ summary.myRejected }}
            </el-tag>
          </span>
          <div class="spacer" />
          <el-button link type="primary" @click="router.push('/inspections?status=DRAFT')">去检验单列表</el-button>
        </div>
      </template>
      <el-table :data="mineSorted" size="small" v-loading="loading" empty-text="没有未完成的单据">
        <el-table-column prop="inspectionNo" label="检验单号" width="160" />
        <el-table-column prop="batchNo" label="批次号" width="150" />
        <el-table-column prop="productName" label="产品" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="rejectedIds.has(row.id)" type="danger" size="small">被退回</el-tag>
            <el-tag v-else type="info" size="small">草稿</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后修改" width="170">
          <template #default="{ row }">{{ fmt(row.updatedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="router.push(`/inspection/${row.id}`)">
              继续填写
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="mineSorted.length < mine.length" class="more">
        共 {{ mine.length }} 张未完成，先显示 {{ mineSorted.length }} 张（被退回的排在前面）
      </div>
    </el-card>

    <ReviewDialog v-model="reviewVisible" :row="reviewTarget" @done="loadAll" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http, { getStoredUser } from '../api/http'
import ReviewDialog from '../components/ReviewDialog.vue'

const router = useRouter()
const user = getStoredUser() || {}

const loading = ref(false)
const summary = ref({})
const reviews = ref([])
const defects = ref([])
const gauges = ref([])
const mine = ref([])
const complaints = ref([])
const batches = ref([])

const reviewVisible = ref(false)
const reviewTarget = ref({})
const pageSize = 8

const canReview = computed(() => user.role === 'ADMIN' || user.role === 'REVIEWER')
/** 需要人动手的那几个（当天刚建批的不算） */
const batchUrgent = computed(() => batches.value.filter(b => b.urgent).length)
const shippedNoInspection = computed(() => batches.value.some(b => b.level === 'SHIPPED'))
const displayName = computed(() => user.realName || user.username || '')
const roleText = computed(
  () => ({ ADMIN: '管理员', INSPECTOR: '检验员', REVIEWER: '审核员' }[user.role] || '未知角色')
)

const today = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'long'
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '辛苦了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const stats = computed(() => {
  const base = []
  if (canReview.value) {
    base.push({
      key: 'review', label: '待我审核', sec: 'sec-review', cls: 'warn',
      count: summary.value.pendingReview || 0, hint: '已提交、等放行'
    })
  }
  base.push(
    {
      key: 'batch', label: '待检批次', sec: 'sec-batch', cls: 'warn',
      count: summary.value.pendingBatch || 0, hint: '建了批次没开单'
    },
    {
      key: 'complaint', label: '未关闭客诉', sec: 'sec-complaint', cls: 'danger',
      count: summary.value.openComplaint || 0, hint: '客户在等答复'
    },
    {
      key: 'defect', label: '待处置不良', sec: 'sec-defect', cls: 'danger',
      count: summary.value.pendingDefect || 0, hint: '含处理中未闭环'
    },
    {
      key: 'gauge', label: '超期量具', sec: 'sec-gauge', cls: 'info',
      count: summary.value.overdueGauge || 0, hint: '超期数据不认'
    },
    {
      key: 'draft', label: '我的草稿', sec: 'sec-mine', cls: 'primary',
      count: summary.value.myDraft || 0, hint: `其中被退回 ${summary.value.myRejected || 0} 张`
    }
  )
  return base
})

/** 被退回的单也躺在草稿里，靠这个集合区分标签 */
const rejectedIds = computed(() => new Set((summary.value.__rejected || []).map(r => r.id)))

/**
 * 工作台只铺前 8 条。这里是「先看有没有事」，不是台账——
 * 真正要翻页找单据，去对应的列表页。
 */
const pagedDefects = computed(() => defects.value.slice(0, pageSize))
const mineSorted = computed(() => {
  const rows = mine.value.slice()
  rows.sort((a, b) => {
    const ra = rejectedIds.value.has(a.id) ? 0 : 1
    const rb = rejectedIds.value.has(b.id) ? 0 : 1
    // 被退回的排前面：那是「你不改就卡住」的单
    return ra - rb || (b.id || 0) - (a.id || 0)
  })
  return rows.slice(0, 12)
})

const typeText = t =>
  ({ IQC: '来料检验', IPQC: '过程检验', FQC: '成品检验', OQC: '出货检验' }[t] || t || '—')
const batchLevelText = l =>
  ({ SHIPPED: '已出货未检', OVERDUE: '超期未检', DUE: '待检', OK: '正常' }[l] || l)
const batchLevelType = l =>
  ({ SHIPPED: 'danger', OVERDUE: 'warning', DUE: 'info', OK: 'success' }[l] || 'info')

const resultText = r => ({ PASS: '合格', FAIL: '不合格', NA: '未判定' }[r] || r || '—')
const resultType = r => ({ PASS: 'success', FAIL: 'danger', NA: 'info' }[r] || 'info')
const dispositionText = d =>
  ({ REWORK: '返工', SCRAP: '报废', CONCESSION: '让步接收', RETURN: '退货' }[d] || '待定')

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

function isSelf(row) {
  return !!row.inspectorName && row.inspectorName === displayName.value
}

function scrollTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function openReview(row) {
  reviewTarget.value = row
  reviewVisible.value = true
}

function goDispose(row) {
  router.push({ path: '/defects', query: { focus: row.id } })
}

function goComplaint(row) {
  router.push({ path: '/complaints', query: { focus: row.id } })
}

function severityText(s) {
  return { HIGH: '严重', MEDIUM: '一般', LOW: '轻微' }[s] || s || '—'
}

function severityType(s) {
  return { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }[s] || 'info'
}

async function loadAll() {
  loading.value = true
  try {
    // 五个数字 + 三张表一次取齐。分开请求会让首屏出现「数字已到、列表还在转」的割裂感
    const [s, m] = await Promise.all([
      http.get('/todo/summary'),
      http.get('/todo/mine')
    ])
    summary.value = { ...s, __rejected: m.rejected || [] }
    mine.value = m.drafts || []

    const [r, d, g, c, b] = await Promise.all([
      canReview.value ? http.get('/todo/reviews', { params: { limit: 50 } }) : Promise.resolve([]),
      http.get('/todo/defects'),
      http.get('/todo/gauges'),
      http.get('/todo/complaints', { params: { limit: 50 } }),
      http.get('/todo/batches', { params: { limit: 20 } })
    ])
    reviews.value = r || []
    defects.value = d || []
    gauges.value = g || []
    complaints.value = c || []
    batches.value = b || []
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.wb { display: flex; flex-direction: column; gap: 16px; }
.hero { display: flex; align-items: center; padding: 4px 2px 0; }
.hi { font-size: 18px; font-weight: 600; color: var(--el-text-color-primary); }
.sub { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 4px; }
.hl { color: var(--el-color-danger); font-size: 15px; }
.spacer { flex: 1; }

.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; }
.stat {
  border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
  padding: 14px 16px; cursor: pointer; background: var(--el-bg-color);
  transition: box-shadow .2s, transform .2s;
}
.stat:hover { box-shadow: var(--el-box-shadow-light); transform: translateY(-1px); }
.stat .num { font-size: 26px; font-weight: 600; line-height: 1.1; }
.stat .label { font-size: 13px; color: var(--el-text-color-primary); margin-top: 6px; }
.stat .hint { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.stat.zero .num { color: var(--el-text-color-placeholder); }
.stat.warn .num { color: var(--el-color-warning); }
.stat.danger .num { color: var(--el-color-danger); }
.stat.info .num { color: var(--el-color-info); }
.stat.primary .num { color: var(--el-color-primary); }

.block :deep(.el-card__header) { padding: 12px 16px; }
.head { display: flex; align-items: center; font-size: 14px; font-weight: 500; }
.ml { margin-left: 6px; }
.mb { margin-bottom: 10px; }
.more { margin-top: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.flag { font-size: 12px; color: var(--el-text-color-regular); line-height: 1.6; }
.ok { color: var(--el-color-success); font-weight: 500; }
.ng { color: var(--el-color-danger); font-weight: 500; }
.sep { color: var(--el-text-color-placeholder); margin: 0 4px; }
.sub2 { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
