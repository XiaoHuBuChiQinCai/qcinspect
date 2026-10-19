<template>
  <el-card>
    <div class="bar">
      <el-select v-model="productId" placeholder="选择产品" style="width: 240px" @change="load">
        <el-option v-for="p in products" :key="p.id" :label="`${p.productCode} ${p.productName}`" :value="p.id" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="standardCode" label="标准编号" width="180" />
      <el-table-column label="版本" width="90">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="{ ACTIVE: 'success', DRAFT: 'info', OBSOLETE: 'danger' }[row.status]" size="small">
            {{ { ACTIVE: '生效中', DRAFT: '草稿', OBSOLETE: '已作废' }[row.status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="抽样方式" width="110">
        <template #default="{ row }">
          {{ { FULL: '全检', SAMPLE: `抽检 ${row.sampleSize} 件`, NONE: '免检' }[row.samplingType] }}
        </template>
      </el-table-column>
      <el-table-column prop="allowedNgCount" label="允许NG项数" width="120" />
      <el-table-column prop="effectiveFrom" label="生效日期" width="130" />
      <el-table-column prop="remark" label="备注" min-width="160" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="publish(row)">发布新版本</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert type="info" :closable="false" class="tip">
      标准不会被原地修改。改版 = 以现有版本为模板发布一个新版本，旧版本自动置为「已作废」，
      已生成的检验单仍引用当初的版本，历史判定不受影响。
    </el-alert>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const products = ref([])
const productId = ref(null)
const rows = ref([])
const loading = ref(false)

async function loadProducts() {
  products.value = await http.get('/products')
  if (products.value.length && !productId.value) {
    productId.value = products.value[0].id
  }
}

async function load() {
  if (!productId.value) return
  loading.value = true
  try {
    rows.value = await http.get('/standards', { params: { productId: productId.value } })
  } finally {
    loading.value = false
  }
}

async function publish(row) {
  await ElMessageBox.confirm(
    `将以 v${row.version} 为模板发布新版本，旧版本自动作废。确认继续？`,
    '发布新版本',
    { type: 'warning' }
  )
  const nw = await http.post(`/standards/${row.id}/publish`)
  ElMessage.success(`已发布 v${nw.version}`)
  await load()
}

onMounted(async () => {
  await loadProducts()
  await load()
})
</script>

<style scoped>
.bar { display: flex; gap: 12px; margin-bottom: 16px; }
.tip { margin-top: 16px; }
</style>
