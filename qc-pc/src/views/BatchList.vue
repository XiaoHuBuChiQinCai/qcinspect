<template>
  <el-card>
    <div class="bar">
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 160px" @change="load">
        <el-option label="待检" value="PENDING" />
        <el-option label="检验中" value="INSPECTING" />
        <el-option label="已完成" value="DONE" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" size="default">
      <el-table-column prop="batchNo" label="批次号" width="170" />
      <el-table-column prop="workOrderNo" label="工单号" width="160" />
      <el-table-column prop="inspectType" label="检验类型" width="110" />
      <el-table-column prop="qty" label="数量" width="90" />
      <el-table-column prop="supplier" label="供应商" min-width="140" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" size="small" :disabled="row.status === 'DONE'" @click="openInspection(row)">
            开检验单
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import http from '../api/http'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const filterStatus = ref('')

const statusText = s => ({ PENDING: '待检', INSPECTING: '检验中', DONE: '已完成' }[s] || s)
const statusType = s => ({ PENDING: 'info', INSPECTING: 'warning', DONE: 'success' }[s] || '')

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/batches', { params: { status: filterStatus.value || undefined } })
  } finally {
    loading.value = false
  }
}

async function openInspection(row) {
  let sampleQty
  try {
    const { value } = await ElMessageBox.prompt(
      '本次检验抽几件？留空则取标准里配置的抽样数。全检时填实际件数。',
      `开检验单 · ${row.batchNo}`,
      {
        inputValue: String(row.qty || 1),
        inputPattern: /^[1-9]\d{0,2}$/,
        inputErrorMessage: '件数需为 1 ~ 200 的整数'
      }
    )
    sampleQty = Number(value)
  } catch {
    return
  }
  // 检验员由后端取登录态，前端不传名字
  const data = await http.post('/inspections', { batchId: row.id, sampleQty })
  router.push(`/inspection/${data.inspectionId}`)
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; gap: 12px; margin-bottom: 16px; }
</style>
