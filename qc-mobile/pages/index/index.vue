<template>
  <view class="page">
    <view class="head">
      <button class="scan" @click="scan">扫码开工单</button>
      <text class="link" @click="load">刷新</text>
      <text class="link" @click="logout">退出</text>
    </view>

    <view class="me" v-if="user">当前：{{ user.realName || user.username }}</view>

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
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api, clearSession, getToken, getUser } from '../../common/api'

const batches = ref([])
const loading = ref(false)
const user = ref(getUser())
const tab = ref('PENDING')

const statusText = s => ({ PENDING: '待检', INSPECTING: '检验中', DONE: '已完成' }[s] || s)

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loading.value = true
  try {
    batches.value = await api.batches(tab.value)
  } finally {
    loading.value = false
  }
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
