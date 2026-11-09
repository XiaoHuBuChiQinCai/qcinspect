<template>
  <view class="page">
    <view class="top">
      <text class="no">{{ info.inspectionNo }}</text>
      <text class="std">标准 v{{ info.standardVersion }} · 批量 {{ info.lotQty || '-' }} · 抽 {{ info.sampleQty }} · 允收 {{ info.acceptQty }} 件</text>
      <text v-if="info.samplingBasis" class="basis">{{ info.samplingBasis }}</text>
    </view>

    <scroll-view scroll-x class="piece-bar" :scroll-into-view="'piece-' + currentPiece">
      <view
        v-for="p in pieces"
        :id="'piece-' + p.pieceNo"
        :key="p.pieceNo"
        :class="['piece-chip', p.pieceNo === currentPiece ? 'on' : '', pieceClass(p)]"
        @click="goPiece(p.pieceNo)"
      >
        第{{ p.pieceNo }}件
      </view>
    </scroll-view>

    <view class="progress">
      <text>已填完 {{ filledPieces }} / {{ pieces.length }} 件</text>
      <text class="cur" @click="goReport">查看报告 ›</text>
    </view>

    <view v-if="locked" class="banner lock">{{ lockText }}</view>

    <!-- 别人建的单：不拦（换班代录是常态，拦了就是共用账号），但得让人知道自己签的是谁的单 -->
    <view v-if="handoverFrom" class="banner handover">
      这张单是 {{ handoverFrom }} 建的，你正在代录。提交时日志会记「代 {{ handoverFrom }} 提交」
    </view>

    <view v-if="judge" :class="['banner', judge.pass ? 'ok' : 'ng']">
      {{ judge.pass ? '批合格' : '批不合格' }} · {{ judge.reason }}
    </view>

    <view v-for="(items, group) in currentGroups" :key="group" class="group">
      <view class="group-head">
        <text class="group-name">{{ group }}</text>
        <text class="group-cnt">{{ filled(items) }}/{{ items.length }}</text>
      </view>

      <view v-for="line in items" :key="line.id"
            :class="['line', line.result === 'FAIL' ? 'line-ng' : '', hintOf(line) === 'oos' ? 'line-oos' : '']">
        <view class="line-head">
          <text class="item-name">{{ line.itemName }}</text>
          <text v-if="line.isCritical" class="flag">关键</text>
          <text class="std-text">{{ stdText(line) }}</text>
          <!-- 录入当场就要看得见，别等提交才发现整批不在规格内 -->
          <text v-if="hintText(line)" class="flag-oos">{{ hintText(line) }}</text>
        </view>

        <view v-if="line.valueType === 'NUMBER' && line.valueMode === 'MULTIPLE'" class="inputs">
          <input
            v-for="i in line.pointCount"
            :key="i"
            :class="['ipt', valueState(line.minValue, line.maxValue, line._inputs[i - 1]) === 'oos' ? 'ipt-oos' : '']"
            type="digit"
            v-model="line._inputs[i - 1]"
            :placeholder="'测点 ' + i"
            :disabled="locked"
          />
        </view>

        <input
          v-else-if="line.valueType === 'NUMBER'"
          :class="['ipt', 'full', valueState(line.minValue, line.maxValue, line._input) === 'oos' ? 'ipt-oos' : '']"
          type="digit"
          v-model="line._input"
          :placeholder="line.unit ? '单位 ' + line.unit : '请输入实测值'"
          :disabled="locked"
        />

        <picker
          v-else-if="line.valueType === 'ENUM'"
          :range="parseOptions(line)"
          range-key="name"
          :disabled="locked"
          @change="e => selectEnum(line, e)"
        >
          <view class="picker">{{ optionName(line) || '请选择' }}</view>
        </picker>

        <view v-else-if="line.valueType === 'BOOL'" class="bool-row">
          <view :class="['bool-btn', line._input === true ? 'on' : '']" @click="setBool(line, true)">有</view>
          <view :class="['bool-btn', line._input === false ? 'on' : '']" @click="setBool(line, false)">无</view>
        </view>

        <textarea v-else v-model="line._text" class="ta" placeholder="补充说明，不参与判定" :disabled="locked" />

        <view v-if="line.valueType === 'NUMBER'" class="gauge-row">
          <text class="gauge-label">量具</text>
          <picker
            :range="choicesFor(line)"
            range-key="label"
            :disabled="locked"
            @change="e => pickGauge(line, e)"
          >
            <view :class="['gauge-pick', line._gaugeId ? 'picked' : '', gaugeBad(line) ? 'bad' : '']">{{ gaugeLabel(line) }}</view>
          </picker>
          <text v-if="line.measureTool && !line._gaugeId" class="gauge-tip">建议 {{ line.measureTool }}</text>
        </view>

        <!-- 选了不能用的量具：当场说清楚，别等 40 个框填完提交才被驳回 -->
        <view v-if="line.valueType === 'NUMBER' && gaugeBad(line)" class="gauge-warn">
          {{ gaugeLabel(line) }} {{ gaugeBad(line).text }}，测出的数据不能采信。请换一把或先送检
        </view>

        <view v-if="line.judgeReason" class="reason">{{ line.judgeReason }}</view>
      </view>
    </view>

    <view class="group">
      <view class="group-head">
        <text class="group-name">现场照片</text>
        <text class="group-cnt">{{ images.length }} 张</text>
      </view>
      <view class="photos">
        <view v-for="img in images" :key="img.id" class="photo">
          <image :src="absUrl(img.url)" mode="aspectFill" class="photo-img" @click="preview(img)" />
          <text v-if="!locked" class="photo-del" @click.stop="delPhoto(img)">×</text>
        </view>
        <view v-if="!locked" class="photo add" @click="addPhoto">＋</view>
      </view>
      <view class="photo-tip">建议拍全：NG 部位特写、量具读数、批次标签。照片是判定被质疑时唯一的现场证据。</view>
    </view>

    <view class="footer">
      <button class="btn ghost" :disabled="currentPiece <= 1 || locked" @click="goPiece(currentPiece - 1)">上一件</button>
      <button class="btn ghost" :disabled="locked" @click="saveDraft">暂存</button>
      <button class="btn submit" :disabled="locked" :loading="submitting" @click="submit">提交</button>
    </view>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { onHide, onLoad, onUnload } from '@dcloudio/uni-app'
import { absUrl, api, getUser, uploadImage } from '../../common/api'
import { judgeGauge } from '../../common/gauge'
import { todayLocal } from '../../common/dict'
import { lineState, valueState, STATE, STATE_TEXT } from '../../common/specHint'
import { DRAFT_TTL_MS, draftKey, pruneIndex, shouldRestore } from '../../common/draft'

const info = ref({})
const pieces = ref([])
const currentPiece = ref(1)
const submitting = ref(false)
const locked = ref(false)
const inspectionId = ref(null)
const gauges = ref([])
const images = ref([])
const me = ref(getUser())

/**
 * 这张单的建单人，若不是当前登录的人就返回他。
 *
 * <p>后端存的是展示名（`UserContext.username()` 给的 displayName），所以拿 realName 比，
 * 别拿账号名比——两个字段对不上，提示永远不出现。
 */
const handoverFrom = computed(() => {
  const by = info.value.createdBy
  const who = me.value && (me.value.realName || me.value.username)
  if (!by || !who || by === who) return null
  return by
})

const lockText = computed(() => {
  const s = info.value.status
  if (s === 'SUBMITTED') {
    return '已提交，等待审核。审核退回后可继续修改；确需立即更正请联系管理员解锁。'
  }
  if (s === 'CLOSED') {
    return '已审核归档，不可修改。如需更正，只能由管理员解锁并在日志中留痕。'
  }
  if (s === 'VOID') {
    return '该检验单已作废，仅可查看。'
  }
  return '已锁定，不可修改。'
})

/** 判定结论直接来自单据本身，刷新后依然在，不用靠提交时的返回值记着 */
const judge = computed(() => {
  const r = info.value.result
  if (r !== 'PASS' && r !== 'FAIL') return null
  return { pass: r === 'PASS', reason: info.value.judgeReason }
})

const currentGroups = computed(() => {
  const p = pieces.value.find(x => x.pieceNo === currentPiece.value)
  return p ? p.groups : {}
})

const filledPieces = computed(() =>
  pieces.value.filter(p => Object.values(p.groups).flat().every(isFilled)).length
)

onLoad(async query => {
  inspectionId.value = query.id
  await loadGauges()
  await load()
})

function parseMeasured(line) {
  if (line.measuredValues) {
    try {
      return JSON.parse(line.measuredValues)
    } catch (e) {
      /* 忽略脏数据 */
    }
  }
  return []
}

async function load() {
  const data = await api.detail(inspectionId.value)
  info.value = data.inspection
  pieces.value = data.pieces
  locked.value = !!data.locked
  currentPiece.value = data.pieces[0] ? data.pieces[0].pieceNo : 1

  pieces.value.forEach(p => {
    Object.values(p.groups).flat().forEach(line => {
      const saved = parseMeasured(line)
      line._inputs = Array.from({ length: line.pointCount || 1 }, (_, i) =>
        saved[i] === undefined ? '' : saved[i]
      )
      if (line.valueType === 'BOOL') {
        line._input = saved.length ? saved[0] === 'true' : null
      } else {
        line._input = saved.length ? saved[0] : ''
      }
      line._text = line.measuredText || ''
      // 已存过的量具选择带回来，否则暂存后再进来量具就丢了
      line._gaugeId = line.gaugeId || null
    })
  })

  await loadImages()
  armed = true
  await maybeRestoreCopy()
}

// ==================== 本机抄本 ====================

/**
 * 服务端「暂存」救不了的场景：车间 wifi 断了，暂存同样发不出去。
 * 而这张单全表几十个测点，填了半小时，手机切个后台被系统回收、
 * 手滑返回、H5 刷一下，内容就全没了——这比少个功能更让人放弃手机端。
 *
 * 所以本地再存一份。它不是第二个权威，只是**等着被提交的抄本**：
 * 提交或暂存成功即删，服务端始终是唯一可信来源。
 */
const COPY_INDEX = 'qc_draft_index'

/** 抄本 key 带了人：公用平板上换个人登录，不能把前一个人填的单当自己的恢复出来 */
const copyKey = computed(() => draftKey(me.value, inspectionId.value))

/** 加载回填期间、只读状态下都不该往本机写 */
let armed = false
let copyTimer = null

function readCopy(key) {
  if (!key) return null
  try {
    const raw = uni.getStorageSync(key)
    if (!raw) return null
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    return obj && Array.isArray(obj.lines) ? obj : null
  } catch (e) {
    return null
  }
}

function readIndex() {
  try {
    const raw = uni.getStorageSync(COPY_INDEX)
    const arr = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(arr) ? arr : []
  } catch (e) {
    return []
  }
}

/**
 * 落一份抄本。顺手清掉过期与超量的旧抄本——
 * localStorage 不会自己回收，攒一年就是几百张单的明文测量值躺在手机上。
 */
function writeCopy() {
  const key = copyKey.value
  if (!key) return
  const now = Date.now()
  try {
    uni.setStorageSync(key, JSON.stringify({ lines: collectLines(), savedAt: now }))
    const idx = readIndex()
    const dead = pruneIndex(idx, key, now, DRAFT_TTL_MS)
    dead.forEach(k => uni.removeStorageSync(k))
    const deadSet = new Set(dead)
    const next = [{ key, savedAt: now }].concat(
      idx
        .filter(e => e && e.key && e.key !== key && !deadSet.has(e.key))
        .sort((a, b) => Number(b.savedAt) - Number(a.savedAt))
    )
    uni.setStorageSync(COPY_INDEX, JSON.stringify(next))
  } catch (e) {
    // 隐私模式或配额满：存不下就算了，不能因为抄本写失败把录入页搞崩
  }
}

function clearCopy() {
  const key = copyKey.value
  if (!key) return
  try {
    uni.removeStorageSync(key)
    uni.setStorageSync(
      COPY_INDEX,
      JSON.stringify(readIndex().filter(e => e && e.key !== key))
    )
  } catch (e) {
    /* 同上 */
  }
}

/** 每次改动后 800ms 落盘。逐字符写会把 localStorage 打成筛子 */
function touchCopy() {
  if (!armed || locked.value) return
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(writeCopy, 800)
}

/**
 * 立刻落盘，不等那 800ms。
 *
 * <p>切后台被系统回收、手滑按返回，正好卡在防抖窗口里的话，
 * 最后改的那几项就白填了——而这恰恰是最常见的丢数据时刻。
 */
function flushCopy() {
  if (copyTimer) {
    clearTimeout(copyTimer)
    copyTimer = null
  }
  if (!armed || locked.value) return
  writeCopy()
}

/**
 * 把抄本贴回页面。按 lineId 对，不按位置对——标准改过版会插行、删行，
 * 按位置贴会把「第 5 项的值」贴到「第 5 项现在是另一个测点」上。
 *
 * @returns 贴回去的行数，0 表示一条都对不上（标准改得面目全非了）
 */
function applyCopy(lines) {
  const map = {}
  ;(lines || []).forEach(l => {
    if (l && l.lineId !== null && l.lineId !== undefined) map[String(l.lineId)] = l
  })
  let hit = 0
  pieces.value.forEach(p => {
    Object.values(p.groups).flat().forEach(line => {
      const saved = map[String(line.id)]
      if (!saved) return
      hit += 1
      const values = Array.isArray(saved.values) ? saved.values : []
      line._inputs = Array.from({ length: line.pointCount || 1 }, (_, i) =>
        values[i] === undefined ? '' : values[i]
      )
      if (line.valueType === 'BOOL') {
        line._input = values.length ? values[0] === 'true' : null
      } else if (line.valueType !== 'TEXT') {
        line._input = values.length ? values[0] : ''
      }
      if (line.valueType === 'TEXT') line._text = saved.textValue || ''
      if (saved.gaugeId) line._gaugeId = saved.gaugeId
    })
  })
  return hit
}

function stampText(ms) {
  const d = new Date(Number(ms))
  if (Number.isNaN(d.getTime())) return '上次'
  const p = n => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function maybeRestoreCopy() {
  const key = copyKey.value
  if (!key) return
  const copy = readCopy(key)
  if (!copy) return

  // 单子已经提交/归档/作废，本机这份就是垃圾
  const st = info.value.status
  if (locked.value || st === 'SUBMITTED' || st === 'CLOSED' || st === 'VOID') {
    clearCopy()
    return
  }

  if (!shouldRestore({ draft: copy, currentLines: collectLines(), now: Date.now() })) {
    // 与服务端一致（人已经暂存过）或者已经过期：留着只会误导
    clearCopy()
    return
  }

  uni.showModal({
    title: '发现没提交的填写',
    content: `${stampText(copy.savedAt)} 在这台手机上填过这张单，还没提交上来。要接着填吗？`,
    confirmText: '接着填',
    cancelText: '丢弃',
    success: res => {
      if (res.confirm) {
        const hit = applyCopy(copy.lines)
        uni.showToast({ title: `已恢复 ${hit} 项`, icon: 'none' })
        writeCopy()
      } else {
        clearCopy()
      }
    }
  })
}

/** 改一处就记一处；deep watch 比在每个 input 上挂事件少漏 20 个绑定 */
watch(pieces, () => touchCopy(), { deep: true })

// 切后台、离开页面都立刻落盘，别等防抖那 800ms
onHide(() => flushCopy())
onUnload(() => flushCopy())

/** 量具台账只在首次进页面拉一次，切件不重复请求 */
async function loadGauges() {
  if (gauges.value.length) return
  try {
    gauges.value = await api.gauges()
  } catch (e) {
    gauges.value = []
  }
}

async function loadImages() {
  try {
    images.value = await api.images('INSPECTION', inspectionId.value)
  } catch (e) {
    images.value = []
  }
}

function goPiece(no) {
  if (no < 1 || no > pieces.value.length || locked.value) return
  currentPiece.value = no
}

function setBool(line, v) {
  if (!locked.value) line._input = v
}

// 录入提示：只影响界面颜色，判定仍以后端为准
const hintOf = line => lineState(line)
const hintText = line => STATE_TEXT[lineState(line)] || ''

function pieceClass(p) {
  const rows = Object.values(p.groups).flat()
  if (rows.some(r => r.result === 'FAIL')) return 'ng'
  return rows.every(isFilled) ? 'done' : ''
}

function isFilled(line) {
  if (line.valueType === 'TEXT') return true
  if (line.valueType === 'BOOL') return line._input === true || line._input === false
  if (line.valueType === 'NUMBER' && line.valueMode === 'MULTIPLE') {
    return line._inputs.every(v => v !== '' && v !== null && v !== undefined)
  }
  return line._input !== '' && line._input !== null && line._input !== undefined
}

function parseOptions(line) {
  if (!line.optionsJson) return []
  try {
    return JSON.parse(line.optionsJson)
  } catch (e) {
    return []
  }
}

function optionName(line) {
  const hit = parseOptions(line).find(o => o.code === line._input)
  return hit ? hit.name : ''
}

function selectEnum(line, e) {
  const opts = parseOptions(line)
  const idx = Number(e.detail.value)
  if (opts[idx]) line._input = opts[idx].code
}

function stdText(line) {
  if (line.valueType === 'NUMBER') {
    const u = line.unit ? ' ' + line.unit : ''
    if (line.minValue != null && line.maxValue != null) return `${line.minValue} ~ ${line.maxValue}${u}`
    if (line.minValue != null) return `≥ ${line.minValue}${u}`
    if (line.maxValue != null) return `≤ ${line.maxValue}${u}`
    return '未设限值'
  }
  if (line.valueType === 'ENUM') {
    return parseOptions(line).map(o => o.name + (o.pass ? '' : '(NG)')).join(' / ')
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

// ==================== 量具 ====================

/**
 * 量具能不能用，判据与后端 `GaugeService.assertUsable` 完全一致（见 common/gauge.js）。
 *
 * 为什么要在选的时候就判：一把超期的卡尺，选的时候不吭声，等 40 个输入框填完
 * 点提交才被驳回「已于 2026-10-01 到期」——那 40 个数字全白填，还得重来。
 */
function gaugeJudge(line) {
  if (!line._gaugeId) return null
  const hit = gauges.value.find(g => g.id === line._gaugeId)
  return judgeGauge(hit, todayLocal())
}

/** 不可用的量具，返回判定结果；可用返回 null。模板里用它控制红字提示 */
function gaugeBad(line) {
  const j = gaugeJudge(line)
  return j && !j.usable ? j : null
}

/**
 * 量具选项：建议的测量手段排最前，不能用的排最后并标出原因。
 * 检验员手上可能同时有卡尺、千分尺、硬度计，按项推荐能少翻一半的台账。
 *
 * 不把不能用的藏掉：他手里那把卡尺就在列表里却找不到，只会以为系统坏了。
 */
function choicesFor(line) {
  const tool = line.measureTool
  const today = todayLocal()
  const list = gauges.value.map(g => {
    const j = judgeGauge(g, today)
    return {
      id: g.id,
      label: g.gaugeCode + ' ' + g.gaugeName + (j.level === 'OK' ? '' : ' · ' + j.text),
      tool: g.gaugeType,
      usable: j.usable,
      hit: !!tool && (g.gaugeType === tool || (g.gaugeName || '').indexOf(tool) >= 0)
    }
  })
  // 可用的排前面，其次按「是否吻合建议量具」；不可用的沉底
  list.sort((a, b) => (b.usable ? 1 : 0) - (a.usable ? 1 : 0) || (b.hit ? 1 : 0) - (a.hit ? 1 : 0))
  return [{ id: null, label: '不记录量具' }].concat(list)
}

function gaugeLabel(line) {
  if (!line._gaugeId) {
    return line.measureTool ? '未选择（建议 ' + line.measureTool + '）' : '未选择'
  }
  const hit = gauges.value.find(g => g.id === line._gaugeId)
  return hit ? hit.gaugeCode : '量具已删除'
}

function pickGauge(line, e) {
  if (locked.value) return
  const opts = choicesFor(line)
  const idx = Number(e.detail.value)
  const pick = opts[idx]
  // picker 没有单项禁用，只能选完再拒：不能让一把停用的卡尺混进提交，
  // 后端提交时会拦这一单，连带同批其他项一起卡住
  if (pick && pick.id && !pick.usable) {
    uni.showToast({ title: pick.label + ' 不能用', icon: 'none' })
    return
  }
  line._gaugeId = pick ? pick.id : null
}

// ==================== 现场照片 ====================

function addPhoto() {
  if (locked.value) return
  uni.chooseImage({
    count: 4,
    sizeType: ['compressed'],
    success: async res => {
      const paths = res.tempFilePaths || []
      if (!paths.length) return
      uni.showLoading({ title: '上传中' })
      try {
        for (const p of paths) {
          await uploadImage(p, 'INSPECTION', inspectionId.value)
        }
        await loadImages()
        uni.showToast({ title: '已上传 ' + paths.length + ' 张', icon: 'none' })
      } finally {
        uni.hideLoading()
      }
    }
  })
}

function preview(img) {
  const urls = images.value.map(i => absUrl(i.url))
  uni.previewImage({ urls, current: absUrl(img.url) })
}

function delPhoto(img) {
  uni.showModal({
    title: '删除照片',
    content: '照片删掉就找不回来了，确定删除？',
    success: async res => {
      if (!res.confirm) return
      await api.removeImage(img.id)
      await loadImages()
    }
  })
}

// ==================== 报告 ====================

function goReport() {
  if (!inspectionId.value) return
  uni.navigateTo({ url: '/pages/report/report?id=' + inspectionId.value })
}

async function saveDraft() {
  if (locked.value) return
  try {
    await api.saveDraft(inspectionId.value, collectLines())
    // 内容已经在服务端了，本机抄本没必要再留着
    clearCopy()
    uni.showToast({ title: '已暂存', icon: 'none' })
  } catch (e) {
    // 暂存同样要过网络，网络差的时候它也会失败——这时候本地那份才是唯一的
    if (e && e.errMsg) {
      writeCopy()
      uni.showToast({ title: '没连上服务器，内容已存在本机', icon: 'none' })
    }
  }
}

async function submit() {
  // isFilled 只判断「非空」，abc 也算填完了；后端会判「数据不完整」，
  // 在提交前说清楚比让人回头去看判定理由直接
  const bad = []
  pieces.value.forEach(p => Object.values(p.groups).flat().forEach(r => {
    if (lineState(r) === STATE.NAN) bad.push(`第 ${p.pieceNo} 件「${r.itemName}」`)
  }))
  if (bad.length) {
    uni.showModal({
      title: '有填错的内容',
      content: `${bad.slice(0, 5).join('、')}${bad.length > 5 ? ' 等' : ''} 不是有效数字`,
      showCancel: false
    })
    return
  }

  const missing = pieces.value
    .map(p => ({ no: p.pieceNo, miss: Object.values(p.groups).flat().filter(r => !isFilled(r)).length }))
    .filter(x => x.miss > 0)
  if (missing.length) {
    uni.showModal({
      title: '还没填完',
      content: `第 ${missing.map(m => m.no).join('、')} 件还有未填项`,
      showCancel: false
    })
    return
  }

  submitting.value = true
  try {
    await api.submit(inspectionId.value, collectLines())
    clearCopy()
    await load()
    uni.showToast({ title: judge.value && judge.value.pass ? '已提交：批合格' : '已提交：批不合格', icon: 'none' })
    uni.pageScrollTo({ scrollTop: 0, duration: 200 })
  } catch (e) {
    // 业务驳回（量具停用之类）上面已经弹过原因了，不必再来一个框。
    // 真正要单独说的是网络断掉这种：数据一个字都没到服务器，人得知道东西还在。
    if (e && e.errMsg) {
      writeCopy()
      uni.showModal({
        title: '没连上服务器',
        content: '这张单还没提交上去。填的内容已经存在这台手机上，网络好了重新进来接着填就行。',
        showCancel: false
      })
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style>
.page { padding: 24rpx 24rpx 200rpx; }
.top { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16rpx; }
.no { font-size: 32rpx; font-weight: 500; }
.std { font-size: 24rpx; color: #8a9199; }
/* 抽样依据：一行小字，但它是这张单「凭什么抽这么多件」的唯一说明，别省 */
.basis { font-size: 22rpx; color: #a8abb2; margin-top: 6rpx; line-height: 1.5; }

.piece-bar { white-space: nowrap; margin-bottom: 12rpx; }
.piece-chip {
  display: inline-block; padding: 12rpx 26rpx; margin-right: 12rpx;
  background: #f5f6f8; border-radius: 30rpx; font-size: 26rpx; color: #5a6b7b;
}
.piece-chip.on { background: #1989fa; color: #fff; }
.piece-chip.done { background: #e8f5e9; color: #4e9a51; }
.piece-chip.ng { background: #fef0f0; color: #c45656; }
.piece-chip.on.ng { background: #c45656; color: #fff; }

.progress { display: flex; justify-content: space-between; font-size: 24rpx; color: #8a9199; margin-bottom: 16rpx; }
.progress .cur { color: #303133; }

.banner { padding: 20rpx 24rpx; border-radius: 12rpx; font-size: 26rpx; margin-bottom: 20rpx; line-height: 1.5; }
.banner.ok { background: #f0f9eb; color: #4e9a51; }
.banner.ng { background: #fef0f0; color: #c45656; }
.banner.lock { background: #fdf6ec; color: #b88230; }
.banner.handover { background: #eef4ff; color: #3a6ea5; }

.group { background: #fff; border-radius: 16rpx; margin-bottom: 20rpx; overflow: hidden; }
.group-head { display: flex; justify-content: space-between; padding: 22rpx 24rpx; background: #fafbfc; }
.group-name { font-size: 28rpx; font-weight: 500; }
.group-cnt { font-size: 24rpx; color: #8a9199; }
.line { padding: 22rpx 24rpx; border-top: 1rpx solid #f0f2f5; }
.line-ng { background: #fef7f7; }
/* 超规但还没提交用暖黄，和「已判 NG」的红区分开：一个还没定论，一个是结论 */
.line-oos { background: #fffbf0; }
.line-head { display: flex; align-items: center; margin-bottom: 14rpx; }
.item-name { font-size: 28rpx; font-weight: 500; }
.flag { font-size: 22rpx; color: #c45656; border: 1rpx solid #c45656; border-radius: 6rpx; padding: 0 8rpx; margin-left: 10rpx; }
.flag-oos { font-size: 22rpx; color: #b45309; background: #fef3c7; border-radius: 6rpx; padding: 0 10rpx; margin-left: 10rpx; }
.std-text { font-size: 22rpx; color: #a8abb2; margin-left: auto; }
.inputs { display: flex; flex-wrap: wrap; gap: 12rpx; }
.ipt { background: #f5f6f8; border-radius: 10rpx; padding: 14rpx 18rpx; font-size: 28rpx; width: 160rpx; }
.ipt.full { width: 100%; }
.ipt-oos { background: #fff5f5; box-shadow: 0 0 0 2rpx #f56c6c inset; }
.picker { background: #f5f6f8; border-radius: 10rpx; padding: 14rpx 18rpx; font-size: 28rpx; color: #303133; }
.bool-row { display: flex; gap: 16rpx; }
.bool-btn { padding: 12rpx 44rpx; border-radius: 10rpx; background: #f5f6f8; font-size: 28rpx; color: #5a6b7b; }
.bool-btn.on { background: #1989fa; color: #fff; }
.ta { width: 100%; background: #f5f6f8; border-radius: 10rpx; padding: 14rpx 18rpx; font-size: 26rpx; height: 80rpx; box-sizing: border-box; }
.reason { font-size: 24rpx; color: #c45656; margin-top: 10rpx; }

.gauge-row { display: flex; align-items: center; margin-top: 14rpx; }
.gauge-label { font-size: 24rpx; color: #8a9199; margin-right: 12rpx; }
.gauge-pick {
  font-size: 24rpx; color: #8a9199; padding: 8rpx 18rpx;
  background: #f5f6f8; border-radius: 8rpx;
}
.gauge-pick.picked { color: #1989fa; background: #eaf3ff; }
.gauge-pick.bad { color: #c45656; background: #fef0f0; }
.gauge-tip { font-size: 22rpx; color: #b0b6bd; margin-left: auto; }
.gauge-warn {
  font-size: 22rpx; color: #c45656; background: #fef0f0;
  border-radius: 8rpx; padding: 8rpx 12rpx; margin-top: 10rpx;
}

.photos { display: flex; flex-wrap: wrap; gap: 14rpx; padding: 22rpx 24rpx; }
.photo { position: relative; width: 150rpx; height: 150rpx; border-radius: 12rpx; overflow: hidden; background: #f5f6f8; }
.photo-img { width: 150rpx; height: 150rpx; }
.photo.add {
  display: flex; align-items: center; justify-content: center;
  font-size: 56rpx; color: #b0b6bd; border: 1rpx dashed #d8dde3; box-sizing: border-box;
}
.photo-del {
  position: absolute; top: 0; right: 0; width: 40rpx; height: 40rpx; line-height: 36rpx;
  text-align: center; background: rgba(0, 0, 0, .45); color: #fff; font-size: 28rpx; border-bottom-left-radius: 12rpx;
}
.photo-tip { padding: 0 24rpx 22rpx; font-size: 22rpx; color: #a8abb2; line-height: 1.5; }

.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 20rpx 24rpx; background: #fff; border-top: 1rpx solid #f0f2f5; display: flex; gap: 16rpx; }
.btn { flex: 1; border-radius: 12rpx; font-size: 30rpx; margin: 0; }
.btn.ghost { background: #f5f6f8; color: #5a6b7b; }
.btn.submit { background: #1989fa; color: #fff; }
</style>
