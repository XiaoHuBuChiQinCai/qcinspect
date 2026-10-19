<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card header="近 30 天检验量">
          <el-table :data="daily" v-loading="loading" size="small">
            <el-table-column prop="day" label="日期" width="120" />
            <el-table-column prop="total" label="检验单数" width="110" />
            <el-table-column prop="pass_cnt" label="合格数" width="110" />
            <el-table-column label="合格率">
              <template #default="{ row }">
                {{ rate(row) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="缺陷帕累托">
          <el-table :data="defects" v-loading="loading" size="small">
            <el-table-column prop="defect_name" label="缺陷项" min-width="140" />
            <el-table-column prop="cnt" label="发生次数" width="110" />
            <el-table-column prop="qty" label="不良数量" width="110" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const daily = ref([])
const defects = ref([])
const loading = ref(false)

function rate(row) {
  const total = Number(row.total || 0)
  const pass = Number(row.pass_cnt || 0)
  if (!total) return '-'
  return ((pass / total) * 100).toFixed(1) + '%'
}

onMounted(async () => {
  loading.value = true
  try {
    const [d, f] = await Promise.all([
      http.get('/inspections/stat/daily'),
      http.get('/inspections/stat/defect')
    ])
    daily.value = d
    defects.value = f
  } finally {
    loading.value = false
  }
})
</script>
