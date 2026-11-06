<template>
  <view class="page">
    <!-- 现场最常问的一句话：这把卡尺现在还能不能用 -->
    <view class="search">
      <input
        v-model="kw"
        class="kw"
        placeholder="量具编号，或扫码"
        confirm-type="search"
        @confirm="find"
      />
      <view class="btn" @click="find">查</view>
      <view class="btn ghost" @click="scan">扫码</view>
    </view>

    <!-- 点检入口：校准一年一次，点检每月一次，现场问得最勤的其实是「这个月点过没」 -->
    <view class="task" @click="gotoCheck()">
      <text class="task-t">{{ taskText }}</text>
      <text class="task-go">去点检 ›</text>
    </view>

    <view v-if="hit" class="detail">
      <view class="dhead">
        <text class="code">{{ hit.gaugeCode }}</text>
        <text :class="['lv', judge.level]">{{ judge.text }}</text>
      </view>
      <text class="dname">{{ hit.gaugeName }}{{ hit.gaugeType ? ' · ' + hit.gaugeType : '' }}</text>

      <view class="kv"><text class="k">状态</text><text class="v">{{ gaugeStatusText(hit.status) }}</text></view>
      <view class="kv"><text class="k">上次校准</text><text class="v">{{ hit.calibrateDate || '—' }}</text></view>
      <view class="kv"><text class="k">下次到期</text><text class="v">{{ hit.nextCalibrateDate || '未校准' }}</text></view>
      <view class="kv"><text class="k">上次点检</text><text class="v">{{ hit.lastCheckDate || '还没点检过' }}</text></view>
      <view class="kv"><text class="k">校准周期</text><text class="v">{{ hit.calibrateCycleDays ? hit.calibrateCycleDays + ' 天' : '—' }}</text></view>
      <view class="kv"><text class="k">使用部门</text><text class="v">{{ hit.ownerDept || '—' }}</text></view>
      <view class="kv" v-if="hit.accuracy"><text class="k">精度</text><text class="v">{{ hit.accuracy }}</text></view>

      <view v-if="!judge.usable" class="block">{{ judge.text }}。{{ gaugeAdvice(judge.level) }}</view>
      <view v-else-if="judge.level === 'SOON'" class="note">{{ judge.text }}，还能用，记得安排送检</view>

      <view v-if="judge.usable" class="go-check" @click="gotoCheck(hit.id)">点检这把量具</view>
      <view v-else class="note">超期、停用、报废的量具不能点检，先按上面的处置走——点检替代不了校准</view>

      <view class="sec">校准履历{{ cals.length ? '（' + cals.length + ' 次）' : '' }}</view>
      <view v-for="c in cals" :key="c.id" class="cal">
        <view class="cal-row">
          <text class="cal-date">{{ c.calibrateDate }}</text>
          <text :class="['cal-res', c.result]">{{ calResultText(c.result) }}</text>
        </view>
        <text class="cal-sub">
          证书 {{ c.certificateNo || '—' }} · {{ c.agency || '未填机构' }} · 到期 {{ c.nextCalibrateDate }}
        </text>
        <text v-if="c.remark" class="cal-sub">{{ c.remark }}</text>
      </view>
      <text v-if="!cals.length" class="empty-inline">还没有校准记录，这把量具不能用于判活</text>
    </view>

    <view class="tabs">
      <view :class="['tab', filter === 'BAD' ? 'on' : '']" @click="filter = 'BAD'">不能用 {{ counts.bad }}</view>
      <view :class="['tab', filter === 'SOON' ? 'on' : '']" @click="filter = 'SOON'">快到期 {{ counts.soon }}</view>
      <view :class="['tab', filter === 'ALL' ? 'on' : '']" @click="filter = 'ALL'">全部 {{ counts.all }}</view>
    </view>

    <view v-for="g in shown" :key="g.id" class="card" @click="select(g)">
      <view class="row">
        <text class="code">{{ g.gaugeCode }}</text>
        <text :class="['lv', g._judge.level]">{{ g._judge.text }}</text>
      </view>
      <text class="dname">{{ g.gaugeName }}{{ g.ownerDept ? ' · ' + g.ownerDept : '' }}</text>
      <text class="sub">下次到期 {{ g.nextCalibrateDate || '未校准' }}</text>
    </view>

    <view v-if="!loading && !shown.length" class="empty">
      {{ filter === 'BAD' ? '没有不能用的量具' : filter === 'SOON' ? '没有 30 天内到期的量具' : '台账里还没有量具' }}
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '../../common/api'
import { normalizeCode } from '../../common/code'
import { todayLocal } from '../../common/dict'
import { calResultText, gaugeAdvice, gaugeStatusText, judgeGauge } from '../../common/gauge'
import { scanCode } from '../../common/lookup'

const list = ref([])
const loading = ref(false)
const kw = ref('')
const filter = ref('BAD')
const hit = ref(null)
const cals = ref([])
const pendingCount = ref(null)

const judge = computed(() => judgeGauge(hit.value, todayLocal()))

/** 点检那一条：数字来自 /gauges/checks/pending，和点检页同一个出处，别在这里另算 */
const taskText = computed(() => {
  if (pendingCount.value === null) return '本月点检'
  return pendingCount.value ? '本月还有 ' + pendingCount.value + ' 把没点检' : '本月点检都做完了'
})

/** 台账拉下来就在本地算一遍结论：一个页面要按结论分组、排序、筛选，不该为此发三次请求 */
const counts = computed(() => {
  let bad = 0
  let soon = 0
  for (const g of list.value) {
    if (!g._judge.usable) bad++
    else if (g._judge.level === 'SOON') soon++
  }
  return { bad, soon, all: list.value.length }
})

const shown = computed(() => {
  if (filter.value === 'BAD') return list.value.filter(g => !g._judge.usable)
  if (filter.value === 'SOON') return list.value.filter(g => g._judge.level === 'SOON')
  return list.value
})

onShow(load)

async function load() {
  loading.value = true
  try {
    const today = todayLocal()
    const raw = await api.gauges()
    list.value = (raw || [])
      .map(g => ({ ...g, _judge: judgeGauge(g, today) }))
      // 不能用的排最前，其次快到期：这一页就是来看「哪几把有问题」的
      .sort((a, b) => rank(a._judge) - rank(b._judge) || String(a.gaugeCode).localeCompare(String(b.gaugeCode)))
    // 点检数量拉不到不该挡住台账
    try {
      const pend = await api.gaugePendingChecks()
      pendingCount.value = (pend || []).length
    } catch (e) {
      pendingCount.value = null
    }
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

function gotoCheck(id) {
  uni.navigateTo({ url: '/pages/gauge/check' + (id ? '?id=' + id : '') })
}

function rank(j) {
  return { SCRAPPED: 0, SUSPENDED: 0, EXPIRED: 0, NOCAL: 1, SOON: 2, OK: 3 }[j.level] ?? 4
}

function select(g) {
  hit.value = g
  cals.value = []
  api.gaugeCalibrations(g.id).then(r => {
    // 履历只增不改，后端按日期倒序给；这里再排一次防接口改排序
    cals.value = (r || []).slice().sort((a, b) => String(b.calibrateDate).localeCompare(String(a.calibrateDate)))
  }).catch(() => {
    cals.value = []
  })
}

/**
 * 按编号找。
 *
 * <p>台账不分页（几十把量具，全量给了才好在本地按结论筛），所以这里直接精确比。
 * 不做模糊匹配：量具编号是现场刻在牌子上的，输错一位就是另一把，
 * 模糊命中的那一把长得一模一样，选错了没人看得出来。
 */
function find() {
  const code = normalizeCode(kw.value)
  if (!code) {
    uni.showToast({ title: '请输入或扫码', icon: 'none' })
    return
  }
  const hitOne = list.value.find(g => normalizeCode(g.gaugeCode) === code)
  if (!hitOne) {
    uni.showToast({ title: '台账里没有 ' + code, icon: 'none' })
    return
  }
  select(hitOne)
}

function scan() {
  // 走公共的「拍照识别」，别直接用 uni.scanCode：H5 端那个 API 根本不支持
  scanCode(code => {
    kw.value = code
    find()
  })
}
</script>

<style scoped>
.page { padding: 20rpx; background: #f5f7fa; min-height: 100vh; }

.search { display: flex; align-items: center; gap: 12rpx; margin-bottom: 20rpx; }
.kw {
  flex: 1; background: #fff; border-radius: 12rpx; padding: 0 20rpx;
  height: 76rpx; font-size: 28rpx;
}
.btn {
  width: 96rpx; height: 76rpx; line-height: 76rpx; text-align: center;
  background: #1989fa; color: #fff; border-radius: 12rpx; font-size: 28rpx;
}
.btn.ghost { background: #fff; color: #1989fa; }

.task {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border-radius: 12rpx; padding: 22rpx 24rpx; margin-bottom: 20rpx;
}
.task-t { font-size: 26rpx; color: #303133; }
.task-go { font-size: 26rpx; color: #1989fa; }

.go-check {
  margin-top: 18rpx; height: 72rpx; line-height: 72rpx; text-align: center;
  border: 1rpx solid #1989fa; color: #1989fa; border-radius: 12rpx; font-size: 26rpx;
}

.detail { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.dhead { display: flex; align-items: center; justify-content: space-between; }
.code { font-size: 32rpx; font-weight: 600; color: #303133; }
.dname { display: block; font-size: 26rpx; color: #606266; margin: 8rpx 0 16rpx; }
.lv { font-size: 24rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.lv.OK { background: #f0f9eb; color: #529b2e; }
.lv.SOON { background: #fdf6ec; color: #b88230; }
.lv.EXPIRED, .lv.SUSPENDED, .lv.SCRAPPED, .lv.NOCAL { background: #fef0f0; color: #c45656; }

.kv { display: flex; justify-content: space-between; padding: 10rpx 0; font-size: 26rpx; }
.k { color: #8a9199; }
.v { color: #303133; }

.block {
  margin-top: 16rpx; padding: 14rpx 18rpx; border-radius: 10rpx;
  background: #fef0f0; color: #c45656; font-size: 24rpx; line-height: 1.6;
}
.note {
  margin-top: 16rpx; padding: 14rpx 18rpx; border-radius: 10rpx;
  background: #fdf6ec; color: #b88230; font-size: 24rpx;
}

.sec { font-size: 26rpx; color: #303133; font-weight: 600; margin: 24rpx 0 12rpx; }
.cal { border-top: 1rpx solid #f0f2f5; padding: 14rpx 0; }
.cal-row { display: flex; justify-content: space-between; }
.cal-date { font-size: 26rpx; color: #303133; }
.cal-res { font-size: 22rpx; padding: 2rpx 10rpx; border-radius: 6rpx; background: #f0f9eb; color: #529b2e; }
.cal-res.FAIL { background: #fef0f0; color: #c45656; }
.cal-res.LIMITED { background: #fdf6ec; color: #b88230; }
.cal-sub { display: block; font-size: 22rpx; color: #8a9199; margin-top: 6rpx; }
.empty-inline { display: block; font-size: 24rpx; color: #a8abb2; padding: 12rpx 0; }

.tabs { display: flex; gap: 12rpx; margin-bottom: 20rpx; }
.tab {
  flex: 1; text-align: center; font-size: 26rpx; padding: 14rpx 0;
  background: #fff; color: #606266; border-radius: 12rpx;
}
.tab.on { background: #1989fa; color: #fff; }

.card { background: #fff; border-radius: 16rpx; padding: 22rpx 24rpx; margin-bottom: 16rpx; }
.row { display: flex; align-items: center; justify-content: space-between; }
.sub { display: block; font-size: 22rpx; color: #8a9199; margin-top: 8rpx; }
.empty { text-align: center; color: #a8abb2; font-size: 26rpx; padding: 60rpx 0; }
</style>
