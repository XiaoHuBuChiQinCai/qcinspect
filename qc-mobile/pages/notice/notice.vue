<template>
  <view class="page">
    <view class="head">
      <text class="title">通知</text>
      <view class="head-right">
        <text v-if="unread" class="link" @click="readAll">全部已读</text>
        <text class="link" @click="load">刷新</text>
      </view>
    </view>

    <view v-for="n in list" :key="n.id" :class="['card', n.readFlag ? 'read' : '']" @click="open(n)">
      <view class="row">
        <text class="n-title">{{ n.title }}</text>
        <text v-if="!n.readFlag" class="dot" />
      </view>
      <view v-if="n.content" class="n-content">{{ n.content }}</view>
      <view class="n-time">{{ n.createdAt }}</view>
    </view>

    <view v-if="!list.length && !loading" class="empty">没有通知</view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api, getToken } from '../../common/api'

const list = ref([])
const unread = ref(0)
const loading = ref(false)

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loading.value = true
  try {
    list.value = await api.notices(false, 50)
    const s = await api.noticeSummary()
    unread.value = s.unread || 0
  } finally {
    loading.value = false
  }
}

async function open(n) {
  if (!n.readFlag) {
    await api.readNotice(n.id)
    n.readFlag = 1
    unread.value = Math.max(0, unread.value - 1)
  }
  // 退回来的单直接进检验录入页，少一次「先去找这张单」的跳转
  if (n.bizType === 'INSPECTION' && n.bizId) {
    uni.navigateTo({ url: `/pages/inspect/inspect?id=${n.bizId}` })
  } else if (n.bizType === 'DEFECT') {
    // 项目没开 tabBar，switchTab 会失败，只能 reLaunch 回待办
    uni.reLaunch({ url: '/pages/todo/todo' })
  }
}

async function readAll() {
  await api.readAllNotices()
  list.value.forEach(n => {
    n.readFlag = 1
  })
  unread.value = 0
  uni.showToast({ title: '已全部标记已读', icon: 'none' })
}

onShow(load)
</script>

<style>
.page { padding: 24rpx 24rpx 60rpx; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.title { font-size: 34rpx; font-weight: 500; }
.head-right { display: flex; gap: 24rpx; }
.link { color: #1989fa; font-size: 28rpx; }

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.card.read { opacity: .62; }
.row { display: flex; align-items: center; justify-content: space-between; }
.n-title { font-size: 29rpx; font-weight: 500; color: #303133; }
.dot { width: 14rpx; height: 14rpx; border-radius: 50%; background: #f56c6c; }
.n-content { margin-top: 10rpx; font-size: 25rpx; color: #5a6b7b; line-height: 1.55; }
.n-time { margin-top: 12rpx; font-size: 22rpx; color: #a8abb2; }

.empty { text-align: center; color: #a8abb2; padding: 120rpx 0; font-size: 26rpx; }
</style>
