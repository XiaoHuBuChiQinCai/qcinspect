<template>
  <view class="page">
    <view class="top">
      <text class="no">{{ info.inspectionNo }}</text>
      <text class="std">标准 v{{ info.standardVersion }} · 允收 {{ info.acceptQty }} 件</text>
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
      <text class="cur">当前第 {{ currentPiece }} 件</text>
    </view>

    <view v-if="locked" class="banner lock">
      已提交锁定，不可修改。需要更正请联系质检主管解锁。
    </view>

    <view v-if="outcome" :class="['banner', outcome.pass ? 'ok' : 'ng']">
      {{ outcome.pass ? '批合格' : '批不合格' }} · {{ outcome.reason }}
    </view>

    <view v-for="(items, group) in currentGroups" :key="group" class="group">
      <view class="group-head">
        <text class="group-name">{{ group }}</text>
        <text class="group-cnt">{{ filled(items) }}/{{ items.length }}</text>
      </view>

      <view v-for="line in items" :key="line.id" :class="['line', line.result === 'FAIL' ? 'line-ng' : '']">
        <view class="line-head">
          <text class="item-name">{{ line.itemName }}</text>
          <text v-if="line.isCritical" class="flag">关键</text>
          <text class="std-text">{{ stdText(line) }}</text>
        </view>

        <view v-if="line.valueType === 'NUMBER' && line.valueMode === 'MULTIPLE'" class="inputs">
          <input
            v-for="i in line.pointCount"
            :key="i"
            class="ipt"
            type="digit"
            v-model="line._inputs[i - 1]"
            :placeholder="'测点 ' + i"
            :disabled="locked"
          />
        </view>

        <input
          v-else-if="line.valueType === 'NUMBER'"
          class="ipt full"
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

        <view v-if="line.judgeReason" class="reason">{{ line.judgeReason }}</view>
      </view>
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
import { api } from '../../common/api'

const info = ref({})
const pieces = ref([])
const currentPiece = ref(1)
const outcome = ref(null)
const submitting = ref(false)
const locked = ref(false)
const inspectionId = ref(null)

const currentGroups = computed(() => {
  const p = pieces.value.find(x => x.pieceNo === currentPiece.value)
  return p ? p.groups : {}
})

const filledPieces = computed(() =>
  pieces.value.filter(p => Object.values(p.groups).flat().every(isFilled)).length
)

onLoad(async query => {
  inspectionId.value = query.id
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
    })
  })
}

function goPiece(no) {
  if (no < 1 || no > pieces.value.length || locked.value) return
  currentPiece.value = no
}

function setBool(line, v) {
  if (!locked.value) line._input = v
}

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
      return { lineId: line.id, values, textValue }
    })
  )
}

async function saveDraft() {
  if (locked.value) return
  await api.saveDraft(inspectionId.value, collectLines())
  uni.showToast({ title: '已暂存', icon: 'none' })
}

async function submit() {
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
    outcome.value = await api.submit(inspectionId.value, collectLines())
    await load()
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
.line-head { display: flex; align-items: center; margin-bottom: 14rpx; }
.item-name { font-size: 28rpx; font-weight: 500; }
.flag { font-size: 22rpx; color: #c45656; border: 1rpx solid #c45656; border-radius: 6rpx; padding: 0 8rpx; margin-left: 10rpx; }
.std-text { font-size: 22rpx; color: #a8abb2; margin-left: auto; }
.inputs { display: flex; flex-wrap: wrap; gap: 12rpx; }
.ipt { background: #f5f6f8; border-radius: 10rpx; padding: 14rpx 18rpx; font-size: 28rpx; width: 160rpx; }
.ipt.full { width: 100%; }
.picker { background: #f5f6f8; border-radius: 10rpx; padding: 14rpx 18rpx; font-size: 28rpx; color: #303133; }
.bool-row { display: flex; gap: 16rpx; }
.bool-btn { padding: 12rpx 44rpx; border-radius: 10rpx; background: #f5f6f8; font-size: 28rpx; color: #5a6b7b; }
.bool-btn.on { background: #1989fa; color: #fff; }
.ta { width: 100%; background: #f5f6f8; border-radius: 10rpx; padding: 14rpx 18rpx; font-size: 26rpx; height: 80rpx; box-sizing: border-box; }
.reason { font-size: 24rpx; color: #c45656; margin-top: 10rpx; }

.footer { position: fixed; left: 0; right: 0; bottom: 0; padding: 20rpx 24rpx; background: #fff; border-top: 1rpx solid #f0f2f5; display: flex; gap: 16rpx; }
.btn { flex: 1; border-radius: 12rpx; font-size: 30rpx; margin: 0; }
.btn.ghost { background: #f5f6f8; color: #5a6b7b; }
.btn.submit { background: #1989fa; color: #fff; }
</style>
