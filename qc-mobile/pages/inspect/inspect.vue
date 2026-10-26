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
            <view :class="['gauge-pick', line._gaugeId ? 'picked' : '']">{{ gaugeLabel(line) }}</view>
          </picker>
          <text v-if="line.measureTool" class="gauge-tip">建议 {{ line.measureTool }}</text>
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
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { absUrl, api, uploadImage } from '../../common/api'
import { lineState, valueState, STATE, STATE_TEXT } from '../../common/specHint'

const info = ref({})
const pieces = ref([])
const currentPiece = ref(1)
const submitting = ref(false)
const locked = ref(false)
const inspectionId = ref(null)
const gauges = ref([])
const images = ref([])

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
}

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
 * 量具选项：建议的测量手段排在最前面。
 * 检验员手上可能同时有卡尺、千分尺、硬度计，按项推荐能少翻一半的台账。
 */
function choicesFor(line) {
  const tool = line.measureTool
  const list = gauges.value.map(g => ({
    id: g.id,
    label: g.gaugeCode + ' ' + g.gaugeName,
    tool: g.gaugeType,
    hit: !!tool && (g.gaugeType === tool || (g.gaugeName || '').indexOf(tool) >= 0)
  }))
  list.sort((a, b) => (b.hit ? 1 : 0) - (a.hit ? 1 : 0))
  return [{ id: null, label: '不记录量具' }].concat(list)
}

function gaugeLabel(line) {
  if (!line._gaugeId) {
    return line.measureTool ? '未选择（建议 ' + line.measureTool + '）' : '未选择'
  }
  const hit = gauges.value.find(g => g.id === line._gaugeId)
  return hit ? hit.gaugeCode : '未选择'
}

function pickGauge(line, e) {
  if (locked.value) return
  const opts = choicesFor(line)
  const idx = Number(e.detail.value)
  const pick = opts[idx]
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
  await api.saveDraft(inspectionId.value, collectLines())
  uni.showToast({ title: '已暂存', icon: 'none' })
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
    await load()
    uni.showToast({ title: judge.value && judge.value.pass ? '已提交：批合格' : '已提交：批不合格', icon: 'none' })
    uni.pageScrollTo({ scrollTop: 0, duration: 200 })
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
.gauge-tip { font-size: 22rpx; color: #b0b6bd; margin-left: auto; }

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
