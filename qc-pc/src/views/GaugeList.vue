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

    <el-alert v-if="suspended.length" type="error" :closable="false" class="mb"
              :title="`${suspended.length} 把量具已停用，不能用于检验`">
      <div class="exp-list">
        <el-tag v-for="g in suspended" :key="g.id" type="danger" size="small">
          {{ g.gaugeCode }} {{ g.gaugeName }}<template v-if="g.suspendReason"> · {{ g.suspendReason }}</template>
        </el-tag>
        <span class="hint">维修后重新登记校准，结果合格即自动恢复可用</span>
      </div>
    </el-alert>

    <el-alert v-if="pendingChecks.length" type="warning" :closable="false" class="mb"
              :title="`${pendingChecks.length} 把量具本月还没点检——每月拿标准件对一遍，漂移要在它测坏一批活之前发现`">
      <div class="exp-list">
        <el-tag v-for="p in pendingChecks" :key="p.gauge.id" type="warning" size="small">
          {{ p.gauge.gaugeCode }} {{ p.gauge.gaugeName }} · {{ checkHint(p) }}
        </el-tag>
      </div>
    </el-alert>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>量具台账（{{ rows.length }} 把）</span>
          <div class="ops">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 150px" @change="load">
              <el-option label="可用" value="ACTIVE" />
              <el-option label="停用（校准或点检不合格）" value="SUSPENDED" />
              <el-option label="已报废" value="SCRAPPED" />
            </el-select>
            <el-button type="primary" @click="openForm()">新增量具</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="gaugeCode" label="编号" width="110" />
        <el-table-column prop="gaugeName" label="名称" min-width="150" />
        <el-table-column prop="gaugeType" label="类型" width="90" />
        <el-table-column prop="accuracy" label="精度" width="90" />
        <el-table-column label="上次校准" width="120">
          <template #default="{ row }">{{ row.calibrateDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="到期日" width="140">
          <template #default="{ row }">
            <span :class="{ expired: isExpired(row), soon: isSoon(row) }">
              {{ row.nextCalibrateDate || '未校准' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="上次点检" width="120">
          <template #default="{ row }">
            <span :class="{ expired: canCheck(row) && !checkedThisMonth(row) }">
              {{ row.lastCheckDate || '未点检' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerDept" label="归属部门" width="100" />
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openHistory(row)">履历</el-button>
            <el-button size="small" :disabled="!canCheck(row)"
                       :title="canCheck(row) ? '' : '超期/停用/报废的量具点检没有意义，先送检校准'"
                       @click="openCheck(row)">点检</el-button>
            <el-button size="small" @click="openForm(row)">编辑</el-button>
            <el-button size="small" type="primary" :disabled="row.status === 'SCRAPPED'" @click="openCalibrate(row)">
              登记校准
            </el-button>
            <el-button size="small" type="danger" :disabled="row.status === 'SCRAPPED'" @click="scrap(row)">
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
          <div class="hint">改这里会补一条履历并注明「编辑时修改」，正规做法是走「登记校准」</div>
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
    <el-dialog v-model="calVisible" title="登记校准" width="500px">
      <el-form label-width="100px">
        <el-form-item label="校准日期" required>
          <el-date-picker v-model="calDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="校准结果">
          <el-radio-group v-model="calResult">
            <el-radio-button value="PASS">合格</el-radio-button>
            <el-radio-button value="FAIL">不合格</el-radio-button>
            <el-radio-button value="LIMITED">限用</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="calResult !== 'FAIL'" label="周期（天）">
          <el-input-number v-model="calCycle" :min="30" :step="30" style="width: 100%" />
        </el-form-item>
        <el-form-item label="证书编号">
          <el-input v-model="calCert" placeholder="如 JL-2026-1024，外审会查" />
        </el-form-item>
        <el-form-item label="校准机构">
          <el-input v-model="calAgency" placeholder="如 市计量院 / 内部校准" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="calRemark"
            type="textarea"
            :rows="2"
            :placeholder="calResult === 'LIMITED' ? '限用必须写清限制范围，如：仅用于 ＜50mm 尺寸' : '选填'"
          />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="calResult === 'FAIL'"
        type="error"
        :closable="false"
        title="不合格不会顺延有效期，量具会置为「停用」——修好重新校准合格后自动恢复可用"
      />
      <el-alert
        v-else-if="backfill"
        type="info"
        :closable="false"
        title="这是补录历史记录：只写履历，不改当前有效期（补录去年的证书不该把有效期改回去）"
      />
      <template #footer>
        <el-button @click="calVisible = false">取消</el-button>
        <el-button type="primary" @click="doCalibrate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 点检（期间核查）：每月拿标准件对一遍 -->
    <el-dialog v-model="chkVisible" title="登记点检" width="520px">
      <el-form label-width="110px">
        <el-form-item label="量具">
          <span>{{ chkTarget?.gaugeCode }} {{ chkTarget?.gaugeName }}</span>
        </el-form-item>
        <el-form-item label="点检日期" required>
          <el-date-picker v-model="chkDate" type="date" value-format="YYYY-MM-DD"
                          :disabled-date="d => d.getTime() > Date.now()" style="width: 100%" />
        </el-form-item>
        <el-form-item label="标准件编号" required>
          <el-input v-model="chkStandardNo" placeholder="刻在标准件上的号，如 STD-10" />
        </el-form-item>
        <el-form-item label="标称值" required>
          <el-input v-model="chkStandardValue" placeholder="标准件上标的 10.000" />
        </el-form-item>
        <el-form-item label="实测值" required>
          <el-input v-model="chkMeasuredValue" placeholder="这把量具量出来多少" />
        </el-form-item>
        <el-form-item label="允差 ±" required>
          <el-input v-model="chkTolerance" :placeholder="chkTarget?.accuracy ? `精度 ${chkTarget.accuracy}，按 ± 填绝对值` : '问清楚允差，别猜'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="chkRemark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false"
                title="结论由后端判定：|实测 − 标称| ≤ 允差 为合格，等于允差算合格。不合格会立刻把这把量具置为停用。" />
      <template #footer>
        <el-button @click="chkVisible = false">取消</el-button>
        <el-button type="primary" @click="doCheck">提交点检</el-button>
      </template>
    </el-dialog>

    <!-- 履历：校准与点检是两件事，分两页看，别混成一张表 -->
    <el-drawer v-model="hisVisible" size="760px" :title="hisTitle">
      <el-tabs v-model="hisTab">
        <el-tab-pane :label="`校准履历（${hisRows.length}）`" name="cal">
          <span class="hint">一次校准一行，只增不改。外审问「去年的证书呢」，答案在这里。</span>
          <el-table :data="hisRows" v-loading="hisLoading" size="small" empty-text="还没有校准记录">
            <el-table-column prop="calibrateDate" label="校准日" width="110" />
            <el-table-column label="结果" width="86">
              <template #default="{ row }">
                <el-tag :type="resultType(row.result)" size="small">{{ resultText(row.result) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="nextCalibrateDate" label="到期日" width="110" />
            <el-table-column prop="agency" label="校准机构" min-width="120">
              <template #default="{ row }">{{ row.agency || '—' }}</template>
            </el-table-column>
            <el-table-column prop="certificateNo" label="证书号" min-width="120">
              <template #default="{ row }">{{ row.certificateNo || '—' }}</template>
            </el-table-column>
            <el-table-column prop="calibrateBy" label="登记人" width="90">
              <template #default="{ row }">{{ row.calibrateBy || '—' }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160">
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`点检履历（${chkRows.length}）`" name="chk">
          <span class="hint">每月一次，拿标准件对一遍。偏差一栏是当时算好的，不随标准件改标而变化。</span>
          <el-table :data="chkRows" size="small" empty-text="还没有点检记录">
            <el-table-column prop="checkDate" label="点检日" width="110" />
            <el-table-column label="结果" width="86">
              <template #default="{ row }">
                <el-tag :type="row.result === 'FAIL' ? 'danger' : 'success'" size="small">
                  {{ row.result === 'FAIL' ? '不合格' : '合格' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="standardNo" label="标准件" width="100" />
            <el-table-column prop="standardValue" label="标称值" width="90" />
            <el-table-column prop="measuredValue" label="实测值" width="90" />
            <el-table-column prop="deviation" label="偏差" width="90" />
            <el-table-column prop="tolerance" label="允差±" width="80" />
            <el-table-column prop="checkedBy" label="点检人" width="90">
              <template #default="{ row }">{{ row.checkedBy || '—' }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="140">
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const rows = ref([])
const allRows = ref([])
const expiring = ref([])
const loading = ref(false)
const statusFilter = ref('')

const formVisible = ref(false)
const editing = ref(false)
const form = reactive({})

const calVisible = ref(false)
const calTarget = ref(null)
const calDate = ref('')
const calCycle = ref(365)
const calResult = ref('PASS')
const calCert = ref('')
const calAgency = ref('')
const calRemark = ref('')

const hisVisible = ref(false)
const hisTarget = ref(null)
const hisRows = ref([])
const hisLoading = ref(false)
const hisTab = ref('cal')
const chkRows = ref([])

// 点检表单
const chkVisible = ref(false)
const chkTarget = ref(null)
const chkDate = ref('')
const chkStandardNo = ref('')
const chkStandardValue = ref('')
const chkMeasuredValue = ref('')
const chkTolerance = ref('')
const chkRemark = ref('')

const pendingChecks = ref([])

const today = new Date(new Date().toDateString())

/** 当月标记用本地年月拼，别用 toISOString：东八区早 8 点前它会给出昨天，跨月就判错 */
const _now = new Date()
const THIS_MONTH = `${_now.getFullYear()}-${String(_now.getMonth() + 1).padStart(2, '0')}`

function checkedThisMonth(row) {
  return !!row.lastCheckDate && String(row.lastCheckDate).slice(0, 7) === THIS_MONTH
}

/** 什么时候才允许点检：在用、且校准还没到期。超期量具点检没有意义，点检替代不了校准 */
function canCheck(row) {
  return row.status === 'ACTIVE' && !!row.nextCalibrateDate && !isExpired(row)
}

/** 待点检那条的说明：天数由后端给（daysSince），前端不再算一遍 */
function checkHint(p) {
  if (!p.lastCheckDate) return '从没点检过'
  return `${p.lastCheckDate} 点过，${p.daysSince} 天前`
}

/** 停用的量具单独提示：它们不在「超期」口径里，但同样不能用 */
const suspended = computed(() => allRows.value.filter(g => g.status === 'SUSPENDED'))

/** 补录旧日期时提醒一句：这次不会改当前有效期 */
const backfill = computed(() => {
  const cur = calTarget.value?.calibrateDate
  return !!(cur && calDate.value && calDate.value < cur)
})

const hisTitle = computed(() =>
  hisTarget.value ? `量具履历 · ${hisTarget.value.gaugeCode} ${hisTarget.value.gaugeName}` : '量具履历'
)

const statusText = s => ({ ACTIVE: '可用', SUSPENDED: '停用', SCRAPPED: '已报废' }[s] || s)
const statusType = s => ({ ACTIVE: 'success', SUSPENDED: 'danger', SCRAPPED: 'info' }[s] || 'info')
const resultText = r => ({ PASS: '合格', FAIL: '不合格', LIMITED: '限用' }[r] || r || '—')
const resultType = r => ({ PASS: 'success', FAIL: 'danger', LIMITED: 'warning' }[r] || 'info')

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
    allRows.value = await http.get('/gauges')
    rows.value = statusFilter.value
      ? allRows.value.filter(g => g.status === statusFilter.value)
      : allRows.value
    expiring.value = await http.get('/gauges/expiring', { params: { days: 30 } })
    // 待点检与「上次点检」列同源：一个来自 /checks/pending，一个来自 list 回填的 lastCheckDate，
    // 都是后端算的，前端只负责显示
    pendingChecks.value = await http.get('/gauges/checks/pending')
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

/** 本地日期。别用 toISOString().slice(0,10)：那是 UTC 日期，东八区早 8 点前会给出昨天 */
function todayLocal() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function openCalibrate(row) {
  calTarget.value = row
  calDate.value = todayLocal()
  calCycle.value = row.calibrateCycleDays || 365
  calResult.value = 'PASS'
  calCert.value = ''
  calAgency.value = ''
  calRemark.value = ''
  calVisible.value = true
}

async function doCalibrate() {
  if (calResult.value === 'LIMITED' && !calRemark.value.trim()) {
    ElMessage.warning('「限用」必须写清限制范围')
    return
  }
  await http.post(`/gauges/${calTarget.value.id}/calibrate`, {
    calibrateDate: calDate.value,
    cycleDays: calCycle.value,
    result: calResult.value,
    certificateNo: calCert.value.trim() || null,
    agency: calAgency.value.trim() || null,
    remark: calRemark.value.trim() || null
  })
  ElMessage.success(
    calResult.value === 'FAIL'
      ? '已登记，量具已停用，修好后重新校准合格即可恢复'
      : backfill.value
        ? '已补录进履历，当前有效期未变'
        : '已登记，到期日已顺延'
  )
  calVisible.value = false
  await load()
}

async function openHistory(row) {
  hisTarget.value = row
  hisVisible.value = true
  hisLoading.value = true
  hisTab.value = 'cal'
  chkRows.value = []
  try {
    hisRows.value = await http.get(`/gauges/${row.id}/calibrations`)
    chkRows.value = await http.get(`/gauges/${row.id}/checks`)
  } finally {
    hisLoading.value = false
  }
}

function openCheck(row) {
  chkTarget.value = row
  chkDate.value = todayLocal()
  chkStandardNo.value = ''
  chkStandardValue.value = ''
  chkMeasuredValue.value = ''
  chkTolerance.value = ''
  chkRemark.value = ''
  chkVisible.value = true
}

async function doCheck() {
  const num = v => (v === '' || v === null ? NaN : Number(v))
  if (!chkStandardNo.value.trim()) {
    ElMessage.warning('填标准件编号——没有标准件的点检，证明不了任何事')
    return
  }
  for (const [label, v] of [['标称值', chkStandardValue.value], ['实测值', chkMeasuredValue.value], ['允差', chkTolerance.value]]) {
    if (!Number.isFinite(num(v))) {
      ElMessage.warning(label + '要填数字')
      return
    }
  }
  const res = await http.post(`/gauges/${chkTarget.value.id}/check`, {
    checkDate: chkDate.value,
    standardNo: chkStandardNo.value.trim(),
    standardValue: num(chkStandardValue.value),
    measuredValue: num(chkMeasuredValue.value),
    tolerance: num(chkTolerance.value),
    remark: chkRemark.value.trim() || null
  })
  chkVisible.value = false
  if (res && res.check && res.check.result === 'FAIL') {
    await load()
    ElMessageBox.alert(
      `${chkTarget.value.gaugeCode} 偏差 ${res.check.deviation}，超出允差 ±${res.check.tolerance}，已置为停用。` +
      '送检复校合格后才能再用；这段期间用它测过的活要回头查。',
      '点检不合格', { type: 'error' }
    )
    return
  }
  ElMessage.success('点检合格，已记录')
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
.ops { display: flex; gap: 10px; align-items: center; }
.mb { margin-bottom: 16px; }
.exp-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; align-items: center; }
.expired { color: var(--el-color-danger); font-weight: 600; }
.soon { color: var(--el-color-warning); }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
.his-head { margin-bottom: 10px; }
</style>
