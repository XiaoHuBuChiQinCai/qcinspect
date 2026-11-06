<template>
  <view class="page">
    <view class="head">
      <button class="scan" @click="scan">扫码开工单</button>
      <text class="link" @click="load">刷新</text>
      <text class="link" @click="logout">退出</text>
    </view>

    <view class="me" v-if="user">当前：{{ user.realName || user.username }}</view>

    <view class="grid">
      <view class="cell" @click="openTodo">
        <text class="cell-title">我的待办</text>
        <text :class="['cell-sub', todoBadge ? 'warn' : '']">{{ todoSub }}</text>
      </view>
      <view class="cell" @click="openTrace">
        <text class="cell-title">追溯查询</text>
        <text class="cell-sub">扫码查批次履历</text>
      </view>
      <view class="cell" @click="openShip">
        <text class="cell-title">出货登记</text>
        <text class="cell-sub">发货时录客户与数量</text>
      </view>
      <view class="cell" @click="openComplaint">
        <text class="cell-title">客诉登记</text>
        <text class="cell-sub">客户反馈当场记下</text>
      </view>
      <view class="cell" @click="openComplaintManage">
        <text class="cell-title">客诉处理</text>
        <text :class="['cell-sub', openComplaintCount ? 'warn' : '']">{{ complaintSub }}</text>
      </view>
      <view class="cell" @click="openGauge">
        <text class="cell-title">量具状态</text>
        <text :class="['cell-sub', summary.overdueGauge ? 'warn' : '']">{{ gaugeSub }}</text>
      </view>
    </view>

    <view class="tabs">
      <view :class="['tab', tab === 'PENDING' ? 'on' : '']" @click="switchTab('PENDING')">待检</view>
      <view :class="['tab', tab === 'INSPECTING' ? 'on' : '']" @click="switchTab('INSPECTING')">检验中</view>
    </view>

    <view v-for="b in batches" :key="b.id" class="card" @click="openBatch(b)">
      <view class="row">
        <text class="no">{{ b.batchNo }}</text>
        <text :class="['tag', b.status]">{{ statusText(b.status) }}</text>
      </view>
      <view class="sub">
        工单 {{ b.workOrderNo || '-' }} · {{ b.inspectType }} · {{ b.qty }} 件
      </view>
      <view class="sub">{{ b.supplier || '内部生产' }}</view>
    </view>

    <view v-if="!loading && !batches.length" class="empty">
      {{ tab === 'PENDING' ? '暂无待检批次' : '没有进行中的检验单' }}
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api, clearSession, getToken, getUser } from '../../common/api'

const batches = ref([])
const loading = ref(false)
const user = ref(getUser())
const tab = ref('PENDING')
const summary = ref({})

const statusText = s => ({ PENDING: '待检', INSPECTING: '检验中', DONE: '已完成' }[s] || s)

/** 待办摘要压成一行小字：手机上四宫格里放不下四个彩色小标签 */
const todoSub = computed(() => {
  const s = summary.value || {}
  const parts = []
  if (s.pendingReview) parts.push('待审 ' + s.pendingReview)
  if (s.pendingDefect) parts.push('待处置 ' + s.pendingDefect)
  if (s.myRejected) parts.push('退回 ' + s.myRejected)
  // 量具超期不在这列：它已经有自己的一格，同一件事出现两遍，人会以为有两件事
  return parts.length ? parts.join(' · ') : '暂无待办'
})

/** 退回是唯一「别人卡着你、你必须动」的事，值得一个角标 */
const todoBadge = computed(() => Number(summary.value?.myRejected) || 0)

/** 量具那格的一句话：超期几把最要紧，没有超期就说清「还有几把快到期」 */
const gaugeSub = computed(() => {
  const overdue = Number(summary.value?.overdueGauge) || 0
  if (overdue) return overdue + ' 把已超期，不能用于判活'
  return '查这把卡尺还能不能用'
})

/**
 * 客诉那格：未关闭的条数。
 *
 * <p>客诉压着两件事——货发不出去（出货会拦），客户还在等答复。
 * 所以有数字就标红，不用「暂无」这种中性词把它稀释掉。
 */
const openComplaintCount = computed(() => Number(summary.value?.openComplaint) || 0)

const complaintSub = computed(() => {
  const n = openComplaintCount.value
  return n ? n + ' 条未关闭，还压着出货' : '没有未关闭的客诉'
})

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loading.value = true
  try {
    batches.value = await api.batches(tab.value)
    // 待办角标拿不到不该挡住批次列表，单独兜一层
    try {
      summary.value = await api.todoSummary()
    } catch (e) {
      summary.value = {}
    }
  } finally {
    loading.value = false
  }
}

function openTodo() {
  uni.navigateTo({ url: '/pages/todo/todo' })
}

function openTrace() {
  uni.navigateTo({ url: '/pages/trace/trace' })
}

function openShip() {
  uni.navigateTo({ url: '/pages/ship/ship' })
}

function openComplaint() {
  uni.navigateTo({ url: '/pages/complaint/complaint' })
}

function openComplaintManage() {
  uni.navigateTo({ url: '/pages/complaint/manage' })
}

function openGauge() {
  uni.navigateTo({ url: '/pages/gauge/gauge' })
}

function switchTab(t) {
  tab.value = t
  load()
}

function logout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账号？',
    success: res => {
      if (!res.confirm) return
      clearSession()
      uni.reLaunch({ url: '/pages/login/login' })
    }
  })
}

function scan() {
  uni.scanCode({
    success: async res => {
      const code = (res.result || '').trim()
      if (!code) return
      // 当前页签里没匹配到就往全部批次里再找一次，扫到的可能是别的状态的批次
      let hit = batches.value.find(b => b.batchNo === code || b.workOrderNo === code)
      if (!hit) {
        const all = await api.batches()
        hit = all.find(b => b.batchNo === code || b.workOrderNo === code)
      }
      if (hit) {
        openBatch(hit)
      } else {
        uni.showToast({ title: '未匹配到批次：' + code, icon: 'none' })
      }
    },
    fail: err => {
      const m = (err && err.errMsg) || ''
      // H5 走 http 时扫码接口不可用（要求安全上下文）；用户主动取消不该弹提示
      if (/not support|不支持|无法使用/i.test(m)) {
        uni.showToast({ title: '当前环境不支持扫码，可在追溯页手输单号', icon: 'none' })
      }
    }
  })
}

/**
 * 打开批次：已有草稿就接着填，已有待审核就看（只读），都没有才新建。
 * 检验员中途退出、手机没电、被叫走都是常事，直接再建一张会留下重复草稿，
 * 更麻烦的是同一批次出现两张有效单，判定结论对不上。
 */
async function openBatch(batch) {
  uni.showLoading({ title: '处理中' })
  try {
    const page = await api.inspectionsOfBatch(batch.id)
    const open = (page.records || []).find(r => r.status === 'DRAFT' || r.status === 'SUBMITTED')
    if (open) {
      uni.navigateTo({ url: `/pages/inspect/inspect?id=${open.id}` })
      return
    }
    const data = await api.createInspection(batch.id)
    uni.navigateTo({ url: `/pages/inspect/inspect?id=${data.inspectionId}` })
  } finally {
    uni.hideLoading()
  }
}

// 每次回到列表都重新拉：刚提交完的批次应该从「检验中」消失
onShow(load)
</script>

<style>
.page { padding: 24rpx; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24rpx; }
.scan { flex: 1; background-color: #1989fa; color: #fff; border-radius: 12rpx; font-size: 32rpx; }
.link { margin-left: 24rpx; color: #1989fa; font-size: 28rpx; }
.me { margin-bottom: 16rpx; font-size: 24rpx; color: #8a9199; }

.grid { display: flex; flex-wrap: wrap; gap: 16rpx; margin-bottom: 24rpx; }
.cell {
  width: calc(50% - 8rpx); box-sizing: border-box;
  background: #fff; border-radius: 16rpx; padding: 24rpx;
}
/* 六格刚好排满三行；.wide 留给将来奇数个入口时跨整行用 */
.cell.wide { width: 100%; }
.cell-title { display: block; font-size: 28rpx; color: #303133; }
.cell-sub { display: block; font-size: 22rpx; color: #a8abb2; margin-top: 10rpx; line-height: 1.4; }
.cell-sub.warn { color: #c45656; }

.tabs { display: flex; gap: 12rpx; margin-bottom: 20rpx; }
.tab {
  flex: 1; text-align: center; padding: 14rpx 0; border-radius: 10rpx;
  background: #eef2f7; color: #5a6b7b; font-size: 27rpx;
}
.tab.on { background: #1989fa; color: #fff; }

.card { background: #fff; border-radius: 16rpx; padding: 28rpx; margin-bottom: 20rpx; }
.row { display: flex; align-items: center; justify-content: space-between; }
.no { font-size: 32rpx; font-weight: 500; }
.tag { font-size: 24rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.tag.PENDING { background: #eef2f7; color: #5a6b7b; }
.tag.INSPECTING { background: #fdf6ec; color: #b88230; }
.tag.DONE { background: #f0f9eb; color: #4e9a51; }
.sub { margin-top: 12rpx; font-size: 26rpx; color: #8a9199; }
.empty { text-align: center; color: #a8abb2; padding: 120rpx 0; }
</style>
