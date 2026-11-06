<template>
  <view class="page">
    <!-- 列表：本月还没点检的量具 -->
    <block v-if="!target">
      <view class="head">
        <view class="htext">
          <text class="title">本月待点检</text>
          <text class="count">{{ loading ? '…' : pending.length + ' 把' }}</text>
        </view>
        <view class="reload" @click="load">刷新</view>
      </view>
      <text class="hint">每月拿标准件对一遍。偏差在允差内算合格；超了这把先停用，送检复校合格才能再用。</text>

      <view v-for="p in pending" :key="p.gauge.id" class="card" @click="pick(p)">
        <view class="row">
          <text class="code">{{ p.gauge.gaugeCode }}</text>
          <text :class="['ago', p.lastCheckDate ? '' : 'never']">{{ pendingHint(p, today) }}</text>
        </view>
        <text class="dname">
          {{ p.gauge.gaugeName }}<text v-if="p.gauge.accuracy"> · 精度 {{ p.gauge.accuracy }}</text>
        </text>
      </view>

      <view v-if="loaded && !pending.length" class="empty">本月的点检都做完了</view>
      <view v-if="failed" class="note" @click="load">没拉到待点检列表，点这里重试</view>
    </block>

    <!-- 表单 -->
    <block v-else>
      <view class="fhead">
        <view class="back" @click="target = null">‹ 返回</view>
        <text class="fcode">{{ target.gaugeCode }}</text>
      </view>
      <text class="dname">{{ target.gaugeName }}<text v-if="target.accuracy"> · 精度 {{ target.accuracy }}</text></text>

      <view class="field">
        <text class="label">标准件编号</text>
        <input v-model="form.standardNo" class="input" placeholder="刻在标准件上的号，如 STD-10" />
      </view>
      <view class="field">
        <text class="label">标称值</text>
        <input v-model="form.standardValue" class="input" type="digit" placeholder="标准件上标的 10.000" />
      </view>
      <view class="field">
        <text class="label">实测值</text>
        <input v-model="form.measuredValue" class="input" type="digit" placeholder="这把量具量出来多少" />
      </view>
      <view class="field">
        <text class="label">允差 ±</text>
        <input v-model="form.tolerance" class="input" type="digit" :placeholder="tolHint" />
      </view>
      <view class="field">
        <text class="label">点检日期</text>
        <picker mode="date" :value="form.checkDate" :end="today" @change="onDate">
          <view class="input pick">{{ form.checkDate }}</view>
        </picker>
      </view>
      <view class="field">
        <text class="label">备注</text>
        <input v-model="form.remark" class="input" placeholder="选填" />
      </view>
      <text v-if="tolFromAccuracy" class="hint small">允差是按精度「{{ target.accuracy }}」预填的，不对就自己改</text>

      <view :class="['verdict', verdict.pass === null ? 'wait' : verdict.pass ? 'ok' : 'bad']">
        {{ verdict.text }}
      </view>
      <view v-if="verdict.pass === false" class="block">
        这一提交，{{ target.gaugeCode }} 会立即停用。已测过的活要回头查，别只把这把放下就完事。
      </view>

      <view class="btn" @click="submit">提交点检</view>

      <text class="sec">最近点检{{ his.length ? '（' + his.length + ' 次）' : '' }}</text>
      <view v-for="c in his" :key="c.id" class="his">
        <view class="row">
          <text class="his-date">{{ c.checkDate }} · 标准件 {{ c.standardNo }}</text>
          <text :class="['res', c.result]">{{ checkResultText(c.result) }}</text>
        </view>
        <text class="his-sub">
          标称 {{ c.standardValue }} · 实测 {{ c.measuredValue }} · 偏差 {{ c.deviation }} · 允差 ±{{ c.tolerance }}
        </text>
        <text v-if="c.remark" class="his-sub">{{ c.remark }}</text>
      </view>
      <text v-if="!his.length" class="empty-inline">这把量具还没有点检记录</text>
    </block>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { api } from '../../common/api'
import { todayLocal } from '../../common/dict'
import {
  checkResultText,
  checkVerdict,
  parseTolerance,
  pendingHint,
  sortPending
} from '../../common/gaugeCheck'

const today = todayLocal()
const pending = ref([])
const loading = ref(false)
const loaded = ref(false)
const failed = ref(false)
const target = ref(null)
const his = ref([])
const tolFromAccuracy = ref(false)

const form = reactive({
  standardNo: '',
  standardValue: '',
  measuredValue: '',
  tolerance: '',
  checkDate: today,
  remark: ''
})

const tolHint = computed(() => {
  const tol = parseTolerance(target.value && target.value.accuracy)
  return tol === null ? '问清楚允差再填，不知道别猜' : '如 ' + tol
})

/** 实时结论：让人在按提交之前就看见「这把要停用了」，而不是提交完才知道 */
const verdict = computed(() => checkVerdict(form.standardValue, form.measuredValue, form.tolerance))

/** 从量具状态页点「点检」进来时带的 id：直接打开这把，不用在列表里翻 */
const wantId = ref(null)
onLoad(opt => {
  const id = Number(opt && opt.id)
  if (id) wantId.value = id
})

onShow(async () => {
  await load()
  openWanted()
})

/**
 * 定位从上一页带过来的那把量具。
 *
 * <p>不在待点检列表里也要能打开：本月点过、或者刚被停用的，人都可能点进来看履历。
 * 停用的量具点不进表单（后端会拒），但历史记录不该藏起来。
 */
function openWanted() {
  if (!wantId.value) return
  const id = wantId.value
  wantId.value = null
  const hit = pending.value.find(p => p.gauge.id === id)
  if (hit) {
    pick(hit)
    return
  }
  api.gauges().then(list => {
    const g = (list || []).find(x => x.id === id)
    if (g) pick({ gauge: g, lastCheckDate: g.lastCheckDate, lastResult: null })
  }).catch(() => {})
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const raw = await api.gaugePendingChecks()
    // 后端已经排过序，这里再排一次防接口改排序；口径在 gaugeCheck.js
    pending.value = sortPending(raw || [], today)
  } catch (e) {
    pending.value = []
    failed.value = true
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function pick(p) {
  const g = p.gauge
  target.value = g
  his.value = []
  form.standardNo = ''
  form.standardValue = ''
  form.measuredValue = ''
  form.checkDate = today
  form.remark = ''

  // 允差按精度预填，认不出就空着——猜一个比空着更危险
  const tol = parseTolerance(g.accuracy)
  form.tolerance = tol === null ? '' : String(tol)
  tolFromAccuracy.value = tol !== null

  api.gaugeChecks(g.id).then(r => {
    his.value = (r || []).slice().sort((a, b) =>
      String(b.checkDate).localeCompare(String(a.checkDate)))
  }).catch(() => {
    his.value = []
  })
}

function onDate(e) {
  form.checkDate = e.detail.value
}

async function submit() {
  if (!form.standardNo.trim()) {
    toast('填标准件编号——没有标准件的点检，证明不了任何事')
    return
  }
  if (verdict.value.pass === null) {
    toast(verdict.value.text)
    return
  }
  uni.showLoading({ title: '提交中' })
  let res
  try {
    res = await api.submitGaugeCheck(target.value.id, {
      checkDate: form.checkDate,
      standardNo: form.standardNo.trim(),
      standardValue: Number(form.standardValue),
      measuredValue: Number(form.measuredValue),
      tolerance: Number(form.tolerance),
      remark: form.remark.trim() || null
    })
  } catch (e) {
    return
  } finally {
    uni.hideLoading()
  }

  const code = target.value.gaugeCode
  if (res && res.check && res.check.result === 'FAIL') {
    // 不合格的后果要说透：不是「这条记录没通过」，是这把量具从此刻起不能用
    target.value = null
    await load()
    uni.showModal({
      title: '已停用',
      content: code + ' 偏差超出允差，已置为停用。送检复校合格后才能再用；这段期间用它测过的活要回头查。',
      showCancel: false
    })
    return
  }
  target.value = null
  await load()
  uni.showToast({ title: '点检合格，已记录', icon: 'none' })
}

function toast(title) {
  uni.showToast({ title, icon: 'none' })
}
</script>

<style scoped>
.page { padding: 20rpx; background: #f5f7fa; min-height: 100vh; }

.head { display: flex; align-items: center; justify-content: space-between; }
.htext { display: flex; align-items: baseline; gap: 12rpx; }
.title { font-size: 32rpx; font-weight: 600; color: #303133; }
.count { font-size: 26rpx; color: #8a9199; }
/* 刷新做成 88rpx 见方的可点块：贴在屏幕边缘的一行小字，拇指根本点不着 */
.reload {
  min-width: 88rpx; height: 64rpx; line-height: 64rpx; text-align: center;
  font-size: 26rpx; color: #1989fa; padding: 0 16rpx;
}
.hint { display: block; font-size: 24rpx; color: #8a9199; line-height: 1.6; margin: 12rpx 0 20rpx; }
.hint.small { margin: 4rpx 0 0; font-size: 22rpx; color: #a8abb2; }

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 16rpx; }
.row { display: flex; align-items: center; justify-content: space-between; }
.code { font-size: 30rpx; font-weight: 600; color: #303133; }
.ago { font-size: 22rpx; color: #b88230; }
.ago.never { color: #c45656; }
.dname { display: block; font-size: 24rpx; color: #606266; margin-top: 8rpx; }

.fhead { display: flex; align-items: center; gap: 16rpx; margin-bottom: 6rpx; }
.back { font-size: 28rpx; color: #1989fa; padding: 8rpx 12rpx 8rpx 0; }
.fcode { font-size: 32rpx; font-weight: 600; color: #303133; }

.field {
  display: flex; align-items: center; background: #fff; border-radius: 12rpx;
  padding: 8rpx 20rpx; margin-top: 16rpx;
}
.label { width: 170rpx; font-size: 26rpx; color: #606266; }
.input {
  flex: 1; height: 76rpx; line-height: 76rpx; font-size: 28rpx; color: #303133;
}
.pick { color: #303133; }

.verdict {
  margin-top: 20rpx; padding: 18rpx; border-radius: 12rpx; font-size: 26rpx; line-height: 1.5;
  background: #f4f4f5; color: #606266;
}
.verdict.ok { background: #f0f9eb; color: #529b2e; }
.verdict.bad { background: #fef0f0; color: #c45656; }

.block {
  margin-top: 14rpx; padding: 16rpx 18rpx; border-radius: 12rpx;
  background: #fef0f0; color: #c45656; font-size: 24rpx; line-height: 1.6;
}

.btn {
  margin-top: 24rpx; height: 88rpx; line-height: 88rpx; text-align: center;
  background: #1989fa; color: #fff; border-radius: 12rpx; font-size: 30rpx;
}

.sec { display: block; font-size: 26rpx; font-weight: 600; color: #303133; margin: 28rpx 0 12rpx; }
.his { background: #fff; border-radius: 12rpx; padding: 18rpx 20rpx; margin-bottom: 12rpx; }
.his-date { font-size: 24rpx; color: #303133; }
.res { font-size: 22rpx; padding: 2rpx 10rpx; border-radius: 6rpx; background: #f0f9eb; color: #529b2e; }
.res.FAIL { background: #fef0f0; color: #c45656; }
.his-sub { display: block; font-size: 22rpx; color: #8a9199; margin-top: 6rpx; line-height: 1.5; }

.empty { text-align: center; color: #a8abb2; font-size: 26rpx; padding: 80rpx 0; }
.empty-inline { display: block; font-size: 24rpx; color: #a8abb2; padding: 12rpx 0; }
.note {
  margin-top: 20rpx; padding: 16rpx; border-radius: 12rpx;
  background: #fdf6ec; color: #b88230; font-size: 24rpx; text-align: center;
}
</style>
