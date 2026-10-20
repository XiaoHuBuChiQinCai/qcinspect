<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-head">
              <span>近 30 天检验量</span>
              <el-button size="small" type="primary" plain @click="exportDaily">导出台账</el-button>
            </div>
          </template>
          <el-table :data="daily" v-loading="loading" size="small">
            <el-table-column prop="day" label="日期" width="120" />
            <el-table-column prop="total" label="检验单" width="90" />
            <el-table-column prop="pass_cnt" label="整单合格" width="100" />
            <el-table-column label="单级合格率" width="110">
              <template #default="{ row }">{{ pct(row.pass_cnt, row.total) }}</template>
            </el-table-column>
            <el-table-column label="件级合格率">
              <template #default="{ row }">{{ pct(row.piece_pass, row.piece_total) }}</template>
            </el-table-column>
          </el-table>
          <div class="tip">
            单级按整张单判 PASS 计；件级按合格件数 / 受检件数计。抽检场景以件级为准。
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-head">
              <span>缺陷帕累托</span>
              <el-button size="small" type="primary" plain @click="exportPareto">导出台账</el-button>
            </div>
          </template>
          <el-table :data="defects" v-loading="loading" size="small">
            <el-table-column prop="defect_name" label="缺陷项" min-width="150" />
            <el-table-column prop="cnt" label="发生次数" width="100" />
            <el-table-column prop="qty" label="不良数量" width="100" />
            <el-table-column label="占比" width="90">
              <template #default="{ row }">{{ share(row) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api/http'
import { downloadCsv } from '../api/download'

const daily = ref([])
const defects = ref([])
const loading = ref(false)

const totalQty = computed(() => defects.value.reduce((s, r) => s + Number(r.qty || 0), 0))

/** 分母为 0 时给「-」，不要给 0%——「没有数据」和「零合格」在质量报表里是两回事 */
function pct(part, total) {
  const t = Number(total || 0)
  if (!t) return '-'
  return ((Number(part || 0) / t) * 100).toFixed(1) + '%'
}

function share(row) {
  return pct(row.qty, totalQty.value)
}

function exportDaily() {
  // 后端会重新查一次库，导出的是完整数据而不是当前页看到的行
  downloadCsv('/export/daily-yield', {}, '日合格率')
}

function exportPareto() {
  downloadCsv('/export/pareto', {}, '不良帕累托')
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

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
