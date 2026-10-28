<template>
  <el-card>
    <div class="bar">
      <el-select v-model="productId" placeholder="选择产品" style="width: 240px" @change="load">
        <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
      </el-select>
      <el-button @click="load">刷新</el-button>
      <div class="spacer" />
      <el-button type="primary" :disabled="!productId" @click="createDraft">新建标准</el-button>
    </div>

    <!-- 改版遗留：旧版判过的单还躺在库里，它们的结论建立在一个已经不作数的口径上。
         只在真有时才出现——常态下没有，那它就不该常驻占位（常驻的空提示会被当装饰品） -->
    <el-alert v-if="stale.rows.length" :type="staleAlertType" :closable="false" class="stale">
      <template #title>
        <div class="stale-head">
          <span>
            {{ stale.versionCount }} 个已作废版本下还有
            <strong>{{ stale.inspectionCount }}</strong> 张已判定单据
            <span class="muted">（其中 {{ stale.failCount }} 张不合格）</span>
            <el-tag v-if="stale.riskText" :type="riskTagType(stale.riskLevel)" size="small"
                    effect="dark" class="stale-risk">{{ stale.riskText }}</el-tag>
            <span class="muted">已复核 {{ stale.reviewedCount || 0 }}/{{ stale.versionCount }}</span>
          </span>
          <el-button link type="primary" size="small" @click="staleOpen = !staleOpen">
            {{ staleOpen ? '收起' : '展开明细' }}
          </el-button>
        </div>
      </template>
      <div class="stale-note">{{ stale.note }}</div>
      <el-table v-if="staleOpen" :data="stale.rows" size="small" class="stale-table">
        <el-table-column label="版本" width="70">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="现行版本" width="90">
          <template #default="{ row }">{{ row.current_version ? `v${row.current_version}` : '无' }}</template>
        </el-table-column>
        <el-table-column label="判定单" width="80">
          <template #default="{ row }">{{ row.inspection_count }} 张</template>
        </el-table-column>
        <el-table-column label="合格 / 不合格" width="120">
          <template #default="{ row }">
            <span class="ok">{{ row.pass_count }}</span>
            <span class="muted"> / </span>
            <span :class="row.fail_count > 0 ? 'ng' : ''">{{ row.fail_count }}</span>
          </template>
        </el-table-column>
        <!-- 风险与方向：同样是「这版判过几张单」，
             收紧后被放行和放宽后被拦下要做的事完全不同 -->
        <el-table-column label="风险" width="110">
          <template #default="{ row }">
            <el-tooltip :content="row.risk_advice" placement="top" effect="dark">
              <el-tag :type="riskTagType(row.risk)" size="small" effect="plain">
                {{ row.risk_text }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <!-- 查没查过：审核问的就是这一句 -->
        <el-table-column label="复核" width="145">
          <template #default="{ row }">
            <template v-if="row.reviewed">
              <el-tag :type="row.review_verdict === 'KEPT' ? 'success' : 'warning'" size="small">
                {{ row.review_verdict_text }}
              </el-tag>
              <div class="stale-tag">{{ row.review_reviewer }} {{ row.review_at }}</div>
            </template>
            <el-tag v-else type="info" size="small" effect="plain">未复核</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后判定" width="110">
          <template #default="{ row }">{{ row.last_used_date || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="185">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openStaleInspections(row)">
              查看这批单
            </el-button>
            <el-button link type="primary" size="small" @click="openStaleReview(row)">
              记录复核
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-alert>

    <el-table :data="rows" v-loading="loading">
      <el-table-column label="标准编号" width="180">
        <template #default="{ row }">{{ row.standard.standardCode }}</template>
      </el-table-column>
      <el-table-column label="版本" width="80">
        <template #default="{ row }">v{{ row.standard.version }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="{ ACTIVE: 'success', DRAFT: 'info', OBSOLETE: 'danger' }[row.standard.status]" size="small">
            {{ { ACTIVE: '生效中', DRAFT: '草稿', OBSOLETE: '已作废' }[row.standard.status] }}
          </el-tag>
          <!-- 作废不等于没事了：这一版判过的单还在。数字直接标在版本上，
               不用再点进提示条才知道「这版还牵扯多少单」 -->
          <div v-if="staleOf(row.standard.id)" class="stale-tag">
            {{ staleOf(row.standard.id) }} 张单按此版判定
          </div>
        </template>
      </el-table-column>
      <el-table-column label="抽样方式" width="120">
        <template #default="{ row }">
          {{ samplingText(row.standard) }}
        </template>
      </el-table-column>
      <el-table-column label="检验项" width="80">
        <template #default="{ row }">
          <span :class="{ warn: !row.itemCount }">{{ row.itemCount }} 项</span>
        </template>
      </el-table-column>
      <el-table-column label="允收数 Ac" width="110">
        <template #default="{ row }">
          <span v-if="row.standard.samplingPlan === 'AQL'" class="muted">按批量查表</span>
          <span v-else>{{ row.standard.allowedNgCount }} 件</span>
        </template>
      </el-table-column>
      <el-table-column label="生效日期" width="120">
        <template #default="{ row }">{{ row.standard.effectiveFrom || '—' }}</template>
      </el-table-column>
      <el-table-column label="备注" min-width="160">
        <template #default="{ row }">{{ row.standard.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <template v-if="row.standard.status === 'DRAFT'">
            <el-button size="small" type="primary" @click="edit(row)">配置</el-button>
            <el-button size="small" @click="removeDraft(row)">删除</el-button>
          </template>
          <el-button v-else-if="row.standard.status === 'ACTIVE'" size="small" @click="publish(row)">
            发布新版本
          </el-button>
          <el-button v-else size="small" @click="edit(row)">查看</el-button>
          <!-- 质量问题追溯时问的是「这标准最近改过什么、是不是放宽了」，
               这个按钮就是那一眼。第一个版本没有更早的版本可比，不给 -->
          <el-button v-if="row.standard.version > 1" size="small" @click="openDiff(row)">对比</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 版本对比：只列判定口径的变化，不列备注、创建时间这些噪声 -->
    <el-dialog v-model="diffVisible" :title="diffTitle" width="760px">
      <div v-if="diffLoading" class="diff-loading">正在比对…</div>
      <template v-else-if="diff">
        <el-alert v-if="diff.firstVersion" type="info" :closable="false"
                  title="这是第一个版本，没有可对比的更早版本" />
        <template v-else>
          <div class="diff-head">
            <el-tag :type="verdictType(diff.verdict)" effect="dark">{{ diff.verdictText }}</el-tag>
            <span class="diff-advice">{{ diff.advice }}</span>
          </div>
          <el-table v-if="diff.changes.length" :data="diff.changes" size="small" class="diff-table">
            <el-table-column label="检验项" min-width="140">
              <template #default="{ row }">{{ row.itemName || row.itemCode }}</template>
            </el-table-column>
            <el-table-column prop="label" label="改了什么" width="120" />
            <el-table-column label="原" width="150">
              <template #default="{ row }">{{ row.before ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="新" width="150">
              <template #default="{ row }">{{ row.after ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="方向" width="90">
              <template #default="{ row }">
                <el-tag :type="directionType(row.direction)" size="small">
                  {{ directionText(row.direction) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-alert v-else type="success" :closable="false" title="两版在判定口径上没有差别" />
        </template>
      </template>
      <template #footer>
        <el-button @click="diffVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <StaleReviewDialog v-model="staleReviewVisible" :row="staleReviewTarget" @done="load" />

    <el-alert type="info" :closable="false" class="tip">
      标准不会被原地修改。改版 = 以现有版本为模板创建下一版<strong>草稿</strong> →
      改完检验项后点「保存并生效」→ 旧版本自动置为「已作废」。
      已生成的检验单仍引用当初的版本，历史判定不受影响。
      点「对比」能看两版之间到底是放宽了还是收紧了 —— 质量问题追溯时，这是第一个要看的。
    </el-alert>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import StaleReviewDialog from '../components/StaleReviewDialog.vue'

const router = useRouter()
const route = useRoute()

const products = ref([])
const productId = ref(null)
const rows = ref([])
const loading = ref(false)

// 改版遗留：已作废版本下还挂着的判定单。三处会用到（顶部提示、状态列数字、明细表），
// 所以只在这里存一份
const stale = ref({
  rows: [], versionCount: 0, inspectionCount: 0, failCount: 0,
  withoutStandard: 0, note: '', riskLevel: null, riskText: null,
  reviewedCount: 0, unreviewedCount: 0
})
const staleOpen = ref(false)
/** 复核弹窗：查过要留痕，否则审核时永远答不出「发现之后做了什么」 */
const staleReviewVisible = ref(false)
const staleReviewTarget = ref({})

function openStaleReview(row) {
  staleReviewTarget.value = row
  staleReviewVisible.value = true
}

/** 提示条的红色是有代价的：只有「收紧后仍放行」这一种才配用红 */
const staleAlertType = computed(
  () => ({ HIGH: 'error', WATCH: 'warning', LOW: 'success' }[stale.value.riskLevel] || 'info')
)
const riskTagType = r => ({ HIGH: 'danger', WATCH: 'warning', LOW: 'success' }[r] || 'info')

const staleMap = computed(() => {
  const m = {}
  for (const r of stale.value.rows) {
    m[r.standard_id] = r.inspection_count
  }
  return m
})
const staleOf = id => staleMap.value[id] || 0

function openStaleInspections(row) {
  // 带上版本号只为在列表页显示一句「仅看 v2」的提示，真正的过滤靠 standardId
  router.push({ path: '/inspections', query: { standardId: row.standard_id, standardVersion: row.version } })
}

function samplingText(std) {
  // AQL 模式下样本量和 Ac 都是批量算出来的，列表上写具体件数会误导
  if (std.samplingPlan === 'AQL') {
    const aql = std.aql == null ? '2.5' : Number(std.aql).toString()
    return `AQL ${aql} · 水平 ${std.inspectionLevel || 'II'}`
  }
  if (std.samplingType === 'SAMPLE') return `抽检 ${std.sampleSize} 件`
  if (std.samplingType === 'NONE') return '免检'
  return '全检'
}

async function loadProducts() {
  products.value = await http.get('/products')
  if (products.value.length && !productId.value) {
    productId.value = products.value[0].id
  }
}

async function load() {
  if (!productId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    // 两个请求一起发：旧版单据的提示和版本列表是同一屏里的东西，
    // 分两次加载会出现「先看到一个没有提示的标准列表，半秒后蹦出一条黄色告警」
    const [list, usage] = await Promise.all([
      http.get('/standards', { params: { productId: productId.value } }),
      // 带上 productId：这一页看的是一个产品，别的产品的旧版单不该混进来。
      // 查询失败不能让整页打不开——提示条是附加信息，没有它照样能改标准
      http.get('/standards/stale-usage', { params: { productId: productId.value } })
        .catch(() => ({ rows: [], versionCount: 0, inspectionCount: 0, failCount: 0, note: '' }))
    ])
    rows.value = list
    stale.value = usage
    // 没有旧版单时把展开状态也复位，否则下次真有数据时会莫名地自己展开
    if (!usage.rows?.length) {
      staleOpen.value = false
    }
  } finally {
    loading.value = false
  }
}

function edit(row) {
  router.push(`/standards/${row.standard.id}`)
}

async function createDraft() {
  const product = products.value.find(p => p.id === productId.value)
  const { value } = await ElMessageBox.prompt('标准编号', '新建标准', {
    inputValue: `ST-${product?.productCode || 'NEW'}`,
    inputPattern: /\S+/,
    inputErrorMessage: '编号不能为空'
  })
  const std = await http.post('/standards', {
    productId: productId.value,
    standardCode: value,
    samplingType: 'SAMPLE',
    sampleSize: 5,
    allowedNgCount: 0
  })
  ElMessage.success('草稿已建立，接着配检验项')
  router.push(`/standards/${std.id}`)
}

async function removeDraft(row) {
  await ElMessageBox.confirm(`删除草稿 v${row.standard.version}？配置的检验项会一并删除。`, '删除草稿', {
    type: 'warning'
  })
  await http.delete(`/standards/${row.standard.id}`)
  ElMessage.success('已删除')
  await load()
}

async function publish(row) {
  await ElMessageBox.confirm(
    `将以 v${row.standard.version} 为模板创建 v${row.standard.version + 1} 草稿。`
    + '新版是草稿，改完检验项后点「保存并生效」，旧版本才会作废。',
    '发布新版本',
    { type: 'warning' }
  )
  const nw = await http.post(`/standards/${row.standard.id}/publish`)
  ElMessage.success(`已创建 v${nw.version} 草稿，接着改要改的项`)
  // 直接进编辑页：发布新版本的唯一目的就是改点东西，
  // 停在列表上还得再点一次「配置」，多一步就多一批忘了改的人
  router.push(`/standards/${nw.id}`)
}

// ---------------- 版本对比 ----------------

const diffVisible = ref(false)
const diffLoading = ref(false)
const diff = ref(null)
const diffTitle = ref('')

async function openDiff(row) {
  diffTitle.value = `v${row.standard.version - 1} → v${row.standard.version} 判定口径对比`
  diff.value = null
  diffLoading.value = true
  diffVisible.value = true
  try {
    diff.value = await http.get(`/standards/${row.standard.id}/diff`)
    if (diff.value?.from) {
      diffTitle.value = `v${diff.value.from.version} → v${diff.value.to.version} 判定口径对比`
    }
  } finally {
    diffLoading.value = false
  }
}

// 放宽是风险，所以红；收紧只是更严格，用绿；方向不明（布尔反转、枚举选项替换）
// 单列黄色——它不是宽严问题，而是「同一份数据结论会反过来」，容易被当成普通变更忽略
const verdictType = v => ({ RELAXED: 'danger', TIGHTENED: 'success', MIXED: 'warning', CHANGED: 'warning' }[v] || 'info')
const directionType = d => ({ LOOSEN: 'danger', TIGHTEN: 'success' }[d] || 'warning')
const directionText = d => ({ LOOSEN: '放宽', TIGHTEN: '收紧' }[d] || '方向变化')

onMounted(async () => {
  await loadProducts()
  // 从主数据页「去配标准」过来时带 productId，省得再选一次
  const preset = Number(route.query.productId)
  if (preset && products.value.some(p => p.id === preset)) {
    productId.value = preset
  }
  await load()
})
</script>

<style scoped>
.bar { display: flex; gap: 12px; margin-bottom: 16px; align-items: center; }
.spacer { flex: 1; }
.tip { margin-top: 16px; }
.stale { margin-bottom: 14px; }
.stale-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.stale-note { font-size: 13px; line-height: 1.6; margin: 6px 0 10px; }
.stale-table { margin-top: 4px; }
.stale-risk { margin-left: 8px; vertical-align: 1px; }
.stale-tag { margin-top: 4px; font-size: 12px; color: var(--el-color-warning); }
.ok { color: var(--el-color-success); }
.ng { color: var(--el-color-danger); font-weight: 600; }
.diff-loading { padding: 24px; text-align: center; color: var(--el-text-color-secondary); }
.diff-head { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 14px; }
.diff-advice { font-size: 13px; line-height: 1.6; color: var(--el-text-color-regular); flex: 1; }
.diff-table { margin-bottom: 4px; }
.warn { color: var(--el-color-danger); }
.muted { color: var(--el-text-color-secondary); }
</style>
