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

    <!-- 返工复检：单独一条，不能只藏在抽样依据的文字里。
         复检单和普通单在判定时完全不同（Ac=0），看单的人必须先知道自己在看哪种 -->
    <el-alert v-if="info.recheckOfId" type="warning" :closable="false" class="recheck-bar">
      <template #title>
        本单是返工复检单 —— 源单
        <el-link type="primary" @click="$router.push(`/inspection/${info.recheckOfId}`)">
          {{ info.recheckOfNo || ('#' + info.recheckOfId) }}
        </el-link>
        的不良返工后复检，逐件全检、Ac=0
      </template>
    </el-alert>

    <!-- 标准改过版：判定结论和依据必须同时在场。只看结论会以为标准一直没动，
         只看版本号又不知道差在哪——所以差异直接列出来，不让人再跳一个页面 -->
    <el-alert
      v-if="info.stdCtx && info.stdCtx.changed"
      :type="info.stdCtx.verdict === 'RELAXED' ? 'warning' : 'info'"
      :closable="false"
      class="std-bar"
    >
      <template #title>
        标准已改版 —— 本单按 v{{ info.stdCtx.usedVersion }} 判定，现行是 v{{
          info.stdCtx.currentVersion
        }}
        <el-tag v-if="info.stdCtx.verdictText" size="small" effect="plain" class="std-verdict">
          {{ info.stdCtx.verdictText }}
        </el-tag>
      </template>
      <div v-if="stdChanges.length" class="std-changes">
        <div v-for="(s, i) in stdChanges" :key="i" class="std-change">· {{ s }}</div>
        <div v-if="stdChangeTotal > stdChanges.length" class="std-more">
          还有 {{ stdChangeTotal - stdChanges.length }} 项差异，去标准页看完整对比
        </div>
      </div>
      <div v-if="info.stdCtx.advice" class="std-advice">{{ info.stdCtx.advice }}</div>
    </el-alert>

    <!-- 抽样依据：检验员看单第一眼要知道「凭什么抽这么多件、凭什么判接收」 -->
    <div v-if="info.samplingBasis" class="sampling-basis">
      <span class="sb-label">抽样依据</span>
      <span>{{ info.samplingBasis }}</span>
    </div>

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
      <el-button
        v-if="!locked && batchColumns.length"
        size="small"
        type="primary"
        plain
        @click="openBatch"
      >
        批量录入
      </el-button>
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

        <div v-for="line in items" :key="line.id" class="line"
             :class="{ ng: line.result === 'FAIL', oos: hintOf(line) === 'oos' }">
          <div class="line-head">
            <span class="name">{{ line.itemName }}</span>
            <el-tag v-if="line.isCritical" type="danger" size="small" effect="plain">关键项</el-tag>
            <span class="std">{{ stdText(line) }}</span>
            <!-- 录入当场就要看得见：等提交判定才报不合格，得回头一件件找是哪项 -->
            <el-tag v-if="hintText(line)" type="warning" size="small" effect="dark">{{ hintText(line) }}</el-tag>
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
                :class="{ oos: valueState(line.minValue, line.maxValue, line._inputs[i - 1]) === 'oos' }"
                :disabled="locked"
              />
            </template>

            <el-input
              v-else-if="line.valueType === 'NUMBER'"
              v-model="line._input"
              :placeholder="`单位 ${line.unit || '-'}`"
              class="point-input"
              :class="{ oos: valueState(line.minValue, line.maxValue, line._input) === 'oos' }"
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

    <!-- 现场照片：与检验单项无关，但审核时是唯一能证明"确实检了"的证据 -->
    <div class="photo-block">
      <div class="photo-head">
        <span class="photo-title">现场照片</span>
        <span class="photo-tip">建议拍：工件整体、NG 部位特写、量具读数</span>
        <div class="spacer" />
        <el-upload
          :show-file-list="false"
          :http-request="uploadPhoto"
          accept="image/*"
          :disabled="locked"
        >
          <el-button size="small" :disabled="locked" :loading="uploading">上传照片</el-button>
        </el-upload>
      </div>
      <div class="photo-list">
        <div v-for="img in images" :key="img.id" class="photo-item">
          <el-image :src="img.url" fit="cover" :preview-src-list="imageUrls" :initial-index="images.indexOf(img)" />
          <el-button v-if="!locked" link type="danger" size="small" @click="removePhoto(img)">删除</el-button>
        </div>
        <div v-if="!images.length" class="photo-empty">暂无照片</div>
      </div>
    </div>

    <!--
      操作轨迹：审核员和主管最常问的两个问题——「谁退回来的」「当时怎么说的」。
      退回理由必须原样完整显示，不能截断，否则等于没记。
    -->
    <div class="log-block">
      <div class="log-head">
        <span class="log-title">操作轨迹</span>
        <span class="log-tip">状态每变一次都会留痕，不可编辑、不可删除</span>
      </div>
      <el-empty v-if="!logs.length" description="暂无操作记录" :image-size="60" />
      <el-timeline v-else class="log-timeline">
        <el-timeline-item
          v-for="l in timeline"
          :key="l.id"
          :type="logType(l.action)"
          :hollow="l.action === 'CREATE'"
          :timestamp="fmtTime(l.createdAt)"
          placement="top"
        >
          <div class="log-row">
            <span class="log-action">{{ logText(l.action) }}</span>
            <span class="log-who">{{ l.operator || '—' }}</span>
            <el-tag v-if="l.fromStatus" size="small" type="info" effect="plain">
              {{ statusText(l.fromStatus) }} → {{ statusText(l.toStatus) }}
            </el-tag>
          </div>
          <div v-if="l.reason" class="log-reason">{{ l.reason }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>

    <!--
      批量录入：现场是一把卡尺逐件量，读数往往就在量具软件里是一列数。
      抽 50 件 × 5 个检验项 = 250 次点击输入，没人干得了，最后就是抽 5 件意思一下——
      SPC 的样本量也是这么没的。这里给一张「件 × 检验项」矩阵，支持从 Excel 整块粘贴。
    -->
    <el-dialog v-model="batchOpen" title="批量录入" width="960px" top="5vh" destroy-on-close>
      <div class="batch-tip">
        只处理<b>数值型</b>检验项<span v-if="nonNumericCount">（另有 {{ nonNumericCount }} 项选择/布尔/文本项不在此表，仍要逐件填）</span>。
        从 Excel 复制一片区域，点矩阵里任意一格 <b>Ctrl+V</b> 直接贴入，按列向右、按件号向下展开；
        只贴一列也可以，贴在对应列第一行即可。
      </div>
      <div class="batch-stat">
        共 {{ batchColumns.length }} 列 × {{ pieces.length }} 件，已填 {{ batchFilled }} 格
        <span v-if="batchOutOfSpec" class="oos-text">
          · 其中 {{ batchOutOfSpec }} 格超规格（标红，写入后照常判 NG）
        </span>
      </div>
      <div ref="batchWrap" class="batch-wrap" tabindex="0" @paste="onPaste">
        <table class="batch-table">
          <thead>
            <tr>
              <th class="col-no">件</th>
              <th v-for="(c, ci) in batchColumns" :key="ci" :title="c.label">
                <div class="col-name">{{ c.label }}</div>
                <div class="col-std">{{ colStd(c) }}</div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, ri) in pieces" :key="p.pieceNo">
              <td class="col-no">{{ p.pieceNo }}</td>
              <td
                v-for="(c, ci) in batchColumns"
                :key="ci"
                :class="{ 'oos-cell': isOos(ri, ci) }"
              >
                <input
                  v-model="batchGrid[ri][ci]"
                  class="cell"
                  :placeholder="ci === 0 && ri === 0 ? '可粘贴' : ''"
                  @focus="focusCell = [ri, ci]"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
        <el-button @click="clearBatch">清空</el-button>
        <el-button @click="batchOpen = false">取消</el-button>
        <el-button type="primary" @click="applyBatch">写入单据</el-button>
      </template>
    </el-dialog>

    <div class="footer">
      <el-button @click="$router.push(`/report/${route.params.id}`)">检验报告</el-button>
      <div class="spacer" />
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
import { parseGrid, isNumeric, isBlank, firstNonNumeric, blit, gridSize } from '../utils/pasteGrid'
import { lineState, valueState, STATE, STATE_TEXT } from '../utils/specHint'
import {
  numericColumns,
  buildGrid,
  applyGrid,
  cellBad,
  outOfSpecCount,
  filledCount
} from '../utils/batchMatrix'

const route = useRoute()
const loading = ref(false)
const submitting = ref(false)
const saving = ref(false)
const info = ref({})
// 差异最多列 4 条：再多就把实测值挤出首屏，而看单的人此刻要看的正是实测值
const stdChanges = computed(() => (info.value.stdCtx?.summary || []).slice(0, 4))
const stdChangeTotal = computed(() => (info.value.stdCtx?.summary || []).length)
const pieces = ref([])
const currentPiece = ref(1)
const activeGroups = ref([])
const outcome = ref(null)
const locked = ref(false)
const gauges = ref([])
const images = ref([])
const uploading = ref(false)
const logs = ref([])

// 后端按 id 倒序返回（新的在前），时间线从旧到新读起来才顺
const timeline = computed(() => [...logs.value].reverse())

const LOG_TEXT = {
  CREATE: '创建检验单',
  SUBMIT: '提交判定',
  REOPEN: '解锁退回录入',
  CLOSE: '归档',
  REVIEW: '审核通过',
  REJECT: '审核退回',
  VOID: '作废'
}
const LOG_TYPE = {
  CREATE: 'primary',
  SUBMIT: 'primary',
  REVIEW: 'success',
  CLOSE: 'success',
  REJECT: 'danger',
  VOID: 'info',
  REOPEN: 'warning'
}
const STATUS_TEXT = { DRAFT: '草稿', SUBMITTED: '待审核', CLOSED: '已归档', VOID: '已作废' }

const logText = a => LOG_TEXT[a] || a || '—'
const logType = a => LOG_TYPE[a] || 'primary'
const statusText = s => STATUS_TEXT[s] || s || '—'

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : ''
}

const imageUrls = computed(() => images.value.map(i => i.url))

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

/* ==================== 批量录入 ==================== */

const batchOpen = ref(false)
const batchGrid = ref([])
const batchWrap = ref(null)
// 粘贴锚点：贴之前得知道从哪一格开始铺
const focusCell = ref([0, 0])

const nonNumericCount = computed(() => {
  const p = pieces.value[0]
  if (!p) return 0
  return Object.values(p.groups).flat().filter(l => l.valueType !== 'NUMBER').length
})

// 列的定义、回写、超规判断都在 utils/batchMatrix.js —— 那层映射错了不会报错，
// 只会把 3 号件的值写到 4 号件上，必须能单测
const batchColumns = computed(() => numericColumns(pieces.value))

function colStd(c) {
  const u = c.unit ? ` ${c.unit}` : ''
  if (c.min != null && c.max != null) return `${c.min}~${c.max}${u}`
  if (c.min != null) return `≥${c.min}${u}`
  if (c.max != null) return `≤${c.max}${u}`
  return '未设限值'
}

function openBatch() {
  batchGrid.value = buildGrid(pieces.value, batchColumns.value)
  focusCell.value = [0, 0]
  batchOpen.value = true
}

const batchFilled = computed(() => filledCount(batchGrid.value))

// 超规或非数字都标红。非数字必须标 —— 否则贴错一列也看不出，等提交才报错
const isOos = (ri, ci) => cellBad(batchColumns.value[ci], batchGrid.value[ri]?.[ci])

const batchOutOfSpec = computed(() =>
  outOfSpecCount(batchColumns.value, batchGrid.value)
)

function clearBatch() {
  batchGrid.value = batchGrid.value.map(row => row.map(() => ''))
}

/** 找出所有「填了但不是数字」的格，返回人话描述 */
function badCells() {
  const bad = []
  batchGrid.value.forEach((row, ri) => {
    row.forEach((v, ci) => {
      // 空格子允许（可以先填一部分）
      if (isBlank(v) || isNumeric(v)) return
      const c = batchColumns.value[ci]
      bad.push(`第 ${pieces.value[ri].pieceNo} 件 · ${c ? c.label : '超范围列'}：「${v}」`)
    })
  })
  return bad
}

function onPaste(e) {
  const ev = e.originalEvent || e
  const text = (ev.clipboardData || window.clipboardData)?.getData('text')
  if (!text) return
  e.preventDefault()

  const grid = parseGrid(text)
  if (!grid.length) return
  const [r0, c0] = focusCell.value

  // 先整块校验再落盘：半截写进去比不写更糟，录入的人会以为贴完了
  const bad = firstNonNumeric(grid)
  if (bad) {
    const c = batchColumns.value[c0 + bad.col]
    ElMessage.error(
      `第 ${r0 + bad.row + 1} 行 · ${c ? c.label : '超范围列'} 的「${bad.raw}」不是数字，整块未写入`
    )
    return
  }

  const written = blit(batchGrid.value, grid, r0, c0)
  if (written < gridSize(grid)) {
    ElMessage.warning('粘贴范围比矩阵大，超出部分已忽略')
  }
  ElMessage.success(`已贴入 ${grid.length} 行 × ${grid[0].length} 列`)
}

function applyBatch() {
  const bad = badCells()
  if (bad.length) {
    ElMessage.error(`有 ${bad.length} 处不是数字：${bad.slice(0, 3).join('；')}`)
    return
  }
  applyGrid(pieces.value, batchColumns.value, batchGrid.value)
  batchOpen.value = false
  ElMessage.success('已写入当前单据。提交判定会一并带上，中途离开请先「暂存」')
}

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

// 录入提示：只影响界面上那一抹红，不参与任何判定结论（判定在后端，有完整快照与口径）
const hintOf = line => lineState(line)
const hintText = line => STATE_TEXT[lineState(line)] || ''

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
    info.value = { ...data.inspection, recheckOfNo: data.recheckOfNo, stdCtx: data.standardContext }
    pieces.value = data.pieces
    locked.value = !!data.locked
    activeGroups.value = Object.keys(data.pieces[0]?.groups || {})
    currentPiece.value = data.pieces[0]?.pieceNo || 1
    initLines()
    gauges.value = await http.get('/gauges', { params: { status: 'ACTIVE' } })
    await Promise.all([loadImages(), loadLogs()])
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  try {
    logs.value = await http.get(`/inspections/${route.params.id}/logs`)
  } catch {
    // 日志接口挂了不该让人录不了数据，但也不能假装有日志
    logs.value = []
  }
}

async function loadImages() {
  try {
    images.value = await http.get('/images', {
      params: { bizType: 'INSPECTION', bizId: route.params.id }
    })
  } catch {
    // 图片接口失败不该阻断录入
    images.value = []
  }
}

async function uploadPhoto(option) {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', option.file)
    fd.append('bizType', 'INSPECTION')
    fd.append('bizId', route.params.id)
    const img = await http.post('/images', fd)
    images.value.push(img)
    ElMessage.success('照片已上传')
  } finally {
    uploading.value = false
  }
}

async function removePhoto(img) {
  await ElMessageBox.confirm('删除这张照片？删除后不可恢复。', '提示', { type: 'warning' })
  await http.delete(`/images/${img.id}`)
  images.value = images.value.filter(i => i.id !== img.id)
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
  // 填了但不是数字的，先挡住。isFilled 只判断「非空」，abc 也会被当成填完了，
  // 后端会判「数据不完整」，到这里来说清楚比让人去看判定理由直接
  const bad = []
  pieces.value.forEach(p => Object.values(p.groups).flat().forEach(r => {
    if (lineState(r) === STATE.NAN) bad.push(`第 ${p.pieceNo} 件「${r.itemName}」`)
  }))
  if (bad.length) {
    ElMessage.error(`有 ${bad.length} 处不是有效数字：${bad.slice(0, 5).join('、')}${bad.length > 5 ? ' 等' : ''}`)
    return
  }

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
.sampling-basis {
  display: flex; align-items: center; gap: 8px;
  margin: 12px 0; padding: 8px 12px;
  background: #f4f6f9; border-radius: 4px;
  font-size: 13px; color: #606266;
}
.sb-label { font-weight: 600; color: #909399; }
.recheck-bar { margin-bottom: 12px; }
.std-bar { margin-bottom: 12px; }
.std-verdict { margin-left: 6px; }
.std-changes { margin-top: 6px; font-size: 12px; line-height: 1.7; }
.std-change { color: var(--el-text-color-regular); }
.std-more { color: var(--el-text-color-secondary); }
.std-advice {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.group-title { font-weight: 500; }
.line { padding: 12px 4px; border-bottom: 1px dashed #ebeef5; }
.line.ng { background: #fef0f0; }
/* 超规但还没提交时用暖黄，和「已判 NG」的红区分开：一个还没定论，一个是结论 */
.line.oos { background: #fffbf0; }
.point-input.oos :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.line-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.line-head .name { font-weight: 500; }
.line-head .std { color: #909399; font-size: 12px; }
.line-body { display: flex; flex-wrap: wrap; gap: 8px; }
.point-input { width: 130px; }
.gauge-select { width: 180px; }
.reason { margin-top: 6px; color: #f56c6c; font-size: 12px; }

.photo-block { margin-top: 24px; padding-top: 16px; border-top: 1px solid #ebeef5; }
.photo-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.photo-title { font-weight: 500; }
.photo-tip { color: #b1b3b8; font-size: 12px; }
.photo-head .spacer { flex: 1; }
.photo-list { display: flex; flex-wrap: wrap; gap: 10px; }
.photo-item { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.photo-item :deep(.el-image) { width: 110px; height: 82px; border-radius: 4px; border: 1px solid #ebeef5; }
.photo-empty { color: #c0c4cc; font-size: 13px; padding: 8px 0; }

.footer { display: flex; align-items: center; margin-top: 24px; }
.footer .spacer { flex: 1; }

.log-block { margin-top: 24px; padding-top: 16px; border-top: 1px solid #ebeef5; }
.log-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 14px; }
.log-title { font-weight: 600; }
.log-tip { font-size: 12px; color: #909399; }
.log-timeline { padding-left: 2px; }
.log-row { display: flex; align-items: center; gap: 8px; }
.log-action { font-weight: 500; }
.log-who { font-size: 12px; color: #909399; }
/* 退回理由整段展示，超长只换行不省略——截断就等于把证据藏起来 */
.log-reason {
  margin-top: 6px; padding: 8px 10px;
  background: #fdf6ec; border-left: 3px solid #e6a23c; border-radius: 2px;
  font-size: 13px; color: #606266; line-height: 1.6;
  white-space: pre-wrap; word-break: break-word;
}

/* ==================== 批量录入 ==================== */
.batch-tip { font-size: 13px; color: #606266; line-height: 1.7; margin-bottom: 10px; }
.batch-stat { font-size: 13px; color: #909399; margin-bottom: 10px; }
.batch-stat .oos-text { color: #f56c6c; }
.batch-wrap { max-height: 56vh; overflow: auto; border: 1px solid #ebeef5; border-radius: 4px; outline: none; }
.batch-table { border-collapse: separate; border-spacing: 0; width: 100%; font-size: 13px; }
.batch-table th, .batch-table td { border-right: 1px solid #ebeef5; border-bottom: 1px solid #ebeef5; padding: 0; }
.batch-table thead th {
  position: sticky; top: 0; z-index: 2;
  background: #f4f6f9; font-weight: 500; padding: 6px 8px;
  text-align: left; white-space: nowrap;
}
.batch-table .col-name { color: #303133; }
.batch-table .col-std { color: #909399; font-size: 11px; font-weight: 400; }
.batch-table th.col-no, .batch-table td.col-no {
  position: sticky; left: 0; z-index: 1;
  width: 46px; min-width: 46px; text-align: center;
  background: #f4f6f9; color: #909399;
}
.batch-table thead th.col-no { z-index: 3; }
.batch-table .cell {
  width: 100%; min-width: 84px; box-sizing: border-box;
  border: none; outline: none; background: transparent;
  padding: 7px 8px; font-size: 13px; color: #303133;
}
.batch-table .cell:focus { background: #ecf5ff; box-shadow: inset 0 0 0 1px #409eff; }
/* 超规的格子当场标红，别等写进单据才发现整批都不在规格内 */
.batch-table td.oos-cell { background: #fef0f0; }
.batch-table td.oos-cell .cell { color: #f56c6c; }
</style>
