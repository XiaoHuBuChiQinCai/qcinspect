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

    <el-alert type="info" :closable="false" class="tip">
      标准不会被原地修改。改版 = 以现有版本为模板创建下一版<strong>草稿</strong> →
      改完检验项后点「保存并生效」→ 旧版本自动置为「已作废」。
      已生成的检验单仍引用当初的版本，历史判定不受影响。
      点「对比」能看两版之间到底是放宽了还是收紧了 —— 质量问题追溯时，这是第一个要看的。
    </el-alert>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const router = useRouter()
const route = useRoute()

const products = ref([])
const productId = ref(null)
const rows = ref([])
const loading = ref(false)

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
    rows.value = await http.get('/standards', { params: { productId: productId.value } })
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
.diff-loading { padding: 24px; text-align: center; color: var(--el-text-color-secondary); }
.diff-head { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 14px; }
.diff-advice { font-size: 13px; line-height: 1.6; color: var(--el-text-color-regular); flex: 1; }
.diff-table { margin-bottom: 4px; }
.warn { color: var(--el-color-danger); }
.muted { color: var(--el-text-color-secondary); }
</style>
