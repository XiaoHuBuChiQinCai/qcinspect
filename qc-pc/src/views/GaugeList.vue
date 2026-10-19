<template>
  <div>
    <el-alert v-if="expiring.length" type="warning" :closable="false" class="mb"
              :title="`有 ${expiring.length} 把量具已过期或 30 天内到期，超期量具测出的数据不可采信`">
      <div class="exp-list">
        <el-tag v-for="g in expiring" :key="g.id" :type="isExpired(g) ? 'danger' : 'warning'" size="small">
          {{ g.gaugeCode }} {{ g.gaugeName }} · {{ g.nextCalibrateDate }}
        </el-tag>
      </div>
    </el-alert>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>量具台账（{{ rows.length }} 把）</span>
          <el-button type="primary" @click="openForm()">新增量具</el-button>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="gaugeCode" label="编号" width="110" />
        <el-table-column prop="gaugeName" label="名称" min-width="150" />
        <el-table-column prop="gaugeType" label="类型" width="100" />
        <el-table-column prop="accuracy" label="精度" width="100" />
        <el-table-column label="上次校准" width="120">
          <template #default="{ row }">{{ row.calibrateDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="到期日" width="150">
          <template #default="{ row }">
            <span :class="{ expired: isExpired(row), soon: isSoon(row) }">
              {{ row.nextCalibrateDate || '未校准' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '可用' : '已报废' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerDept" label="归属部门" width="110" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openForm(row)">编辑</el-button>
            <el-button size="small" type="primary" :disabled="row.status !== 'ACTIVE'" @click="openCalibrate(row)">
              登记校准
            </el-button>
            <el-button size="small" type="danger" :disabled="row.status !== 'ACTIVE'" @click="scrap(row)">
              报废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="formVisible" :title="editing ? '编辑量具' : '新增量具'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="量具编号" required>
          <el-input v-model="form.gaugeCode" placeholder="G-004" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.gaugeName" placeholder="数显卡尺 0-150" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.gaugeType" style="width: 100%">
            <el-option v-for="t in ['卡尺', '千分尺', '硬度计', 'CMM', '塞规', '其他']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="精度">
          <el-input v-model="form.accuracy" placeholder="0.01mm" />
        </el-form-item>
        <el-form-item label="上次校准" required>
          <el-date-picker v-model="form.calibrateDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="校准周期">
          <el-input-number v-model="form.calibrateCycleDays" :min="30" :step="30" style="width: 100%" />
          <div class="hint">天。到期日 = 校准日 + 周期，由后端算</div>
        </el-form-item>
        <el-form-item label="归属部门">
          <el-input v-model="form.ownerDept" placeholder="品质部" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 登记校准 -->
    <el-dialog v-model="calVisible" title="登记校准" width="420px">
      <el-form label-width="90px">
        <el-form-item label="校准日期">
          <el-date-picker v-model="calDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="周期（天）">
          <el-input-number v-model="calCycle" :min="30" :step="30" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="calVisible = false">取消</el-button>
        <el-button type="primary" @click="doCalibrate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const rows = ref([])
const expiring = ref([])
const loading = ref(false)

const formVisible = ref(false)
const editing = ref(false)
const form = reactive({})

const calVisible = ref(false)
const calDate = ref('')
const calCycle = ref(365)
const calTarget = ref(null)

const today = new Date(new Date().toDateString())

function isExpired(g) {
  return g.nextCalibrateDate && new Date(g.nextCalibrateDate) < today
}

function isSoon(g) {
  if (!g.nextCalibrateDate || isExpired(g)) return false
  const diff = (new Date(g.nextCalibrateDate) - today) / 86400000
  return diff <= 30
}

async function load() {
  loading.value = true
  try {
    rows.value = await http.get('/gauges')
    expiring.value = await http.get('/gauges/expiring', { params: { days: 30 } })
  } finally {
    loading.value = false
  }
}

function openForm(row) {
  editing.value = !!row
  Object.keys(form).forEach(k => delete form[k])
  if (row) {
    Object.assign(form, { ...row })
  } else {
    Object.assign(form, { gaugeCode: '', gaugeName: '', gaugeType: '卡尺', accuracy: '', calibrateDate: '', calibrateCycleDays: 365, ownerDept: '品质部' })
  }
  formVisible.value = true
}

async function save() {
  await http.post('/gauges', { ...form })
  ElMessage.success('已保存')
  formVisible.value = false
  await load()
}

function openCalibrate(row) {
  calTarget.value = row
  calDate.value = new Date().toISOString().slice(0, 10)
  calCycle.value = row.calibrateCycleDays || 365
  calVisible.value = true
}

async function doCalibrate() {
  await http.post(`/gauges/${calTarget.value.id}/calibrate`, null, {
    params: { calibrateDate: calDate.value, cycleDays: calCycle.value }
  })
  ElMessage.success('已登记，到期日已顺延')
  calVisible.value = false
  await load()
}

async function scrap(row) {
  const { value } = await ElMessageBox.prompt('报废原因', `报废 ${row.gaugeCode}`, {
    inputPlaceholder: '如：跌落损坏、示值超差无法修复'
  })
  await http.post(`/gauges/${row.id}/scrap`, null, { params: { reason: value } })
  ElMessage.success('已报废，今后不可再被选用')
  await load()
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.mb { margin-bottom: 16px; }
.exp-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.expired { color: var(--el-color-danger); font-weight: 600; }
.soon { color: var(--el-color-warning); }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
