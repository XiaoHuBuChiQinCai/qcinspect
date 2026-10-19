<template>
  <div v-loading="loading">
    <el-page-header content="" @back="$router.back()">
      <template #content>
        <span class="title">
          {{ info.inspectionNo }} · {{ info.inspectType }} · 标准 v{{ info.standardVersion }}
          <el-tag v-if="info.result === 'PASS'" type="success" size="small">合格</el-tag>
          <el-tag v-else-if="info.result === 'FAIL'" type="danger" size="small">不合格</el-tag>
          <el-tag v-else-if="info.status === 'VOID'" type="info" size="small">已作废</el-tag>
          <el-tag v-else type="info" size="small">未判定</el-tag>
          <el-tag v-if="locked" type="warning" size="small">已锁定</el-tag>
        </span>
      </template>
    </el-page-header>

    <el-alert v-if="locked" type="warning" :closable="false" class="result">
      <template #title>
        本单已提交锁定，不可修改。确需更正请走「解锁」并填写理由，操作会留痕。
      </template>
      <el-button size="small" style="margin-top: 8px" @click="reopen">申请解锁</el-button>
    </el-alert>

    <el-alert v-if="outcome" :type="outcome.pass ? 'success' : 'error'" :closable="false" class="result">
      <template #title>
        整批判定：{{ outcome.pass ? '合格' : '不合格' }} —— {{ outcome.reason }}
      </template>
      <div class="piece-results">
        <el-tag
          v-for="p in outcome.pieces"
          :key="p.pieceNo"
          :type="p.pass ? 'success' : 'danger'"
          size="small"
          effect="plain"
          class="piece-tag"
        >
          第 {{ p.pieceNo }} 件 {{ p.pass ? 'OK' : 'NG' }}
        </el-tag>
      </div>
    </el-alert>

    <div class="piece-bar">
      <el-button :disabled="currentPiece <= 1" @click="goPiece(currentPiece - 1)">上一件</el-button>
      <el-select v-model="currentPiece" style="width: 200px" @change="onPieceChange">
        <el-option
          v-for="p in pieces"
          :key="p.pieceNo"
          :value="p.pieceNo"
          :label="`第 ${p.pieceNo} 件 · ${pieceStatus(p)}`"
        />
      </el-select>
      <el-button :disabled="currentPiece >= pieces.length" @click="goPiece(currentPiece + 1)">下一件</el-button>
      <span class="piece-stat">
        共 {{ pieces.length }} 件，已填完 {{ filledPieces }} 件（允收 {{ info.acceptQty }} 件）
      </span>
      <el-button v-if="!locked" size="small" :loading="saving" @click="saveDraft(false)">暂存</el-button>
    </div>

    <el-collapse v-model="activeGroups">
      <el-collapse-item v-for="(items, group) in currentGroups" :key="group" :name="group">
        <template #title>
          <span class="group-title">{{ group }}</span>
          <el-tag size="small" type="info" style="margin-left: 8px">
            {{ filled(items) }}/{{ items.length }}
          </el-tag>
        </template>

        <div v-for="line in items" :key="line.id" class="line" :class="{ ng: line.result === 'FAIL' }">
          <div class="line-head">
            <span class="name">{{ line.itemName }}</span>
            <el-tag v-if="line.isCritical" type="danger" size="small" effect="plain">关键项</el-tag>
            <span class="std">{{ stdText(line) }}</span>
            <el-tag v-if="line.result === 'FAIL'" type="danger" size="small">NG</el-tag>
            <el-tag v-else-if="line.result === 'PASS'" type="success" size="small">OK</el-tag>
          </div>

          <div class="line-body">
            <template v-if="line.valueType === 'NUMBER' && line.valueMode === 'MULTIPLE'">
              <el-input
                v-for="i in line.pointCount"
                :key="i"
                v-model="line._inputs[i - 1]"
                :placeholder="`测点 ${i}`"
                class="point-input"
                :disabled="locked"
              />
            </template>

            <el-input
              v-else-if="line.valueType === 'NUMBER'"
              v-model="line._input"
              :placeholder="`单位 ${line.unit || '-'}`"
              class="point-input"
              :disabled="locked"
            />

            <el-select
              v-else-if="line.valueType === 'ENUM'"
              v-model="line._input"
              placeholder="请选择"
              style="width: 220px"
              :disabled="locked"
            >
              <el-option v-for="op in parseOptions(line)" :key="op.code" :label="op.name" :value="op.code" />
            </el-select>

            <el-radio-group v-else-if="line.valueType === 'BOOL'" v-model="line._input" :disabled="locked">
              <el-radio :value="true">有</el-radio>
              <el-radio :value="false">无</el-radio>
            </el-radio-group>

            <el-input
              v-else
              v-model="line._text"
              type="textarea"
              :rows="1"
              placeholder="补充说明，不参与判定"
              :disabled="locked"
            />

            <!-- 量具：数值项才需要。超期量具不可选，因为选了后端也会拦 -->
            <el-select
              v-if="line.valueType === 'NUMBER'"
              v-model="line._gaugeId"
              size="small"
              placeholder="用量具"
              clearable
              filterable
              class="gauge-select"
              :disabled="locked"
            >
              <el-option
                v-for="g in gauges"
                :key="g.id"
                :label="`${g.gaugeCode} ${g.gaugeName}${isGaugeExpired(g) ? '（已过期）' : ''}`"
                :value="g.id"
                :disabled="isGaugeExpired(g)"
              />
            </el-select>
          </div>

          <div v-if="line.judgeReason" class="reason">{{ line.judgeReason }}</div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <div class="footer">
      <el-button v-if="!locked" type="primary" size="large" :loading="submitting" @click="submit">
        提交判定
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const route = useRoute()
const loading = ref(false)
const submitting = ref(false)
const saving = ref(false)
const info = ref({})
const pieces = ref([])
const currentPiece = ref(1)
const activeGroups = ref([])
const outcome = ref(null)
const locked = ref(false)
const gauges = ref([])

function isGaugeExpired(g) {
  if (!g.nextCalibrateDate) return true
  return new Date(g.nextCalibrateDate) < new Date(new Date().toDateString())
}

const currentGroups = computed(() => {
  const p = pieces.value.find(x => x.pieceNo === currentPiece.value)
  return p ? p.groups : {}
})

const filledPieces = computed(() =>
  pieces.value.filter(p => Object.values(p.groups).flat().every(isFilled)).length
)

function parseMeasured(line) {
  if (line.measuredValues) {
    try {
      return JSON.parse(line.measuredValues)
    } catch {
      /* 历史脏数据按空处理 */
    }
  }
  return []
}

function initLines() {
  pieces.value.forEach(p => {
    Object.values(p.groups).flat().forEach(line => {
      const saved = parseMeasured(line)
      line._inputs = Array.from({ length: line.pointCount || 1 }, (_, i) => saved[i] ?? '')
      if (line.valueType === 'BOOL') {
        line._input = saved.length ? saved[0] === 'true' : null
      } else {
        line._input = saved.length ? saved[0] : ''
      }
      line._text = line.measuredText || ''
      line._gaugeId = line.gaugeId || null
    })
  })
}

function isFilled(line) {
  if (line.valueType === 'TEXT') return true
  if (line.valueType === 'BOOL') return line._input !== null && line._input !== undefined
  if (line.valueType === 'NUMBER' && line.valueMode === 'MULTIPLE') {
    return line._inputs.every(v => v !== '' && v !== null && v !== undefined)
  }
  return line._input !== '' && line._input !== null && line._input !== undefined
}

function pieceStatus(p) {
  const rows = Object.values(p.groups).flat()
  if (rows.some(r => r.result === 'FAIL')) return 'NG'
  const miss = rows.filter(r => !isFilled(r)).length
  return miss === 0 ? '已填完' : `待填 ${miss} 项`
}

async function load() {
  loading.value = true
  try {
    const data = await http.get(`/inspections/${route.params.id}`)
    info.value = data.inspection
    pieces.value = data.pieces
    locked.value = !!data.locked
    activeGroups.value = Object.keys(data.pieces[0]?.groups || {})
    currentPiece.value = data.pieces[0]?.pieceNo || 1
    initLines()
    gauges.value = await http.get('/gauges', { params: { status: 'ACTIVE' } })
  } finally {
    loading.value = false
  }
}

function goPiece(no) {
  if (no < 1 || no > pieces.value.length) return
  currentPiece.value = no
  onPieceChange()
}

async function onPieceChange() {
  if (!locked.value) {
    await saveDraft(true)
  }
}

function parseOptions(line) {
  if (!line.optionsJson) return []
  try {
    return JSON.parse(line.optionsJson)
  } catch {
    return []
  }
}

function stdText(line) {
  if (line.valueType === 'NUMBER') {
    const u = line.unit ? ` ${line.unit}` : ''
    if (line.minValue != null && line.maxValue != null) return `${line.minValue} ~ ${line.maxValue}${u}`
    if (line.minValue != null) return `≥ ${line.minValue}${u}`
    if (line.maxValue != null) return `≤ ${line.maxValue}${u}`
    return '未设限值'
  }
  if (line.valueType === 'ENUM') {
    return parseOptions(line).map(o => `${o.name}(${o.pass ? '合格' : 'NG'})`).join(' / ')
  }
  if (line.valueType === 'BOOL') return line.boolPassValue ? '合格值：有' : '合格值：无'
  return '不参与判定'
}

function filled(items) {
  return items.filter(isFilled).length
}

function collectLines() {
  return pieces.value.flatMap(p =>
    Object.values(p.groups).flat().map(line => {
      let values = []
      let textValue = null
      if (line.valueType === 'TEXT') {
        textValue = line._text
      } else if (line.valueType === 'NUMBER' && line.valueMode === 'MULTIPLE') {
        values = line._inputs.filter(v => v !== '').map(String)
      } else {
        values = line._input !== '' && line._input !== null && line._input !== undefined
          ? [String(line._input)]
          : []
      }
      return { lineId: line.id, values, textValue, gaugeId: line._gaugeId || null }
    })
  )
}

async function saveDraft(silent) {
  saving.value = true
  try {
    await http.post(`/inspections/${route.params.id}/save`, { lines: collectLines() })
    if (!silent) ElMessage.success('已暂存')
  } finally {
    saving.value = false
  }
}

async function submit() {
  const missing = pieces.value
    .map(p => ({ no: p.pieceNo, miss: Object.values(p.groups).flat().filter(r => !isFilled(r)).length }))
    .filter(x => x.miss > 0)
  if (missing.length) {
    ElMessage.warning(`还有未填完的件：第 ${missing.map(m => m.no).join('、')} 件`)
    return
  }

  submitting.value = true
  try {
    outcome.value = await http.post(`/inspections/${route.params.id}/submit`, { lines: collectLines() })
    ElMessage.success('判定完成')
    await load()
  } finally {
    submitting.value = false
  }
}

async function reopen() {
  try {
    const { value } = await ElMessageBox.prompt('解锁后本单回到草稿状态，请说明原因（会写入操作日志）', '申请解锁', {
      inputPlaceholder: '例如：第 3 件实测值录错',
      inputValidator: v => (v && v.trim() ? true : '理由不能为空')
    })
    await http.post(`/inspections/${route.params.id}/reopen`, null, { params: { reason: value } })
    ElMessage.success('已解锁')
    outcome.value = null
    await load()
  } catch (e) {
    if (e !== 'cancel') throw e
  }
}

onMounted(load)
</script>

<style scoped>
.title { display: flex; align-items: center; gap: 8px; font-weight: 500; }
.result { margin: 16px 0; }
.piece-results { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.piece-tag { margin: 0; }
.piece-bar { display: flex; align-items: center; gap: 8px; margin: 16px 0; flex-wrap: wrap; }
.piece-stat { color: #909399; font-size: 13px; margin-right: auto; }
.group-title { font-weight: 500; }
.line { padding: 12px 4px; border-bottom: 1px dashed #ebeef5; }
.line.ng { background: #fef0f0; }
.line-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.line-head .name { font-weight: 500; }
.line-head .std { color: #909399; font-size: 12px; }
.line-body { display: flex; flex-wrap: wrap; gap: 8px; }
.point-input { width: 130px; }
.gauge-select { width: 180px; }
.reason { margin-top: 6px; color: #f56c6c; font-size: 12px; }
.footer { margin-top: 24px; text-align: right; }
</style>
