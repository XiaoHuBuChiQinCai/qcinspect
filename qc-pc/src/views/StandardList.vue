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
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <template v-if="row.standard.status === 'DRAFT'">
            <el-button size="small" type="primary" @click="edit(row)">配置</el-button>
            <el-button size="small" @click="removeDraft(row)">删除</el-button>
          </template>
          <el-button v-else-if="row.standard.status === 'ACTIVE'" size="small" @click="publish(row)">
            发布新版本
          </el-button>
          <el-button v-else size="small" @click="edit(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert type="info" :closable="false" class="tip">
      标准不会被原地修改。改版 = 以现有版本为模板发布一个新版本，旧版本自动置为「已作废」，
      已生成的检验单仍引用当初的版本，历史判定不受影响。新建的标准是草稿，配好检验项后点「保存并生效」。
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
    `将以 v${row.standard.version} 为模板发布新版本，旧版本自动作废。确认继续？`,
    '发布新版本',
    { type: 'warning' }
  )
  const nw = await http.post(`/standards/${row.standard.id}/publish`)
  ElMessage.success(`已发布 v${nw.version}`)
  await load()
}

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
.warn { color: var(--el-color-danger); }
.muted { color: var(--el-text-color-secondary); }
</style>
