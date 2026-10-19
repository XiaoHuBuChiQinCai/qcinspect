<template>
  <view class="page">
    <view class="head">
      <button class="scan" @click="scan">扫码开工单</button>
      <text class="link" @click="load">刷新</text>
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

    <view v-if="!loading && !batches.length" class="empty">暂无待检批次</view>
  </view>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../../common/api'

const batches = ref([])
const loading = ref(false)

const statusText = s => ({ PENDING: '待检', INSPECTING: '检验中', DONE: '已完成' }[s] || s)

async function load() {
  loading.value = true
  try {
    batches.value = await api.batches('PENDING')
  } finally {
    loading.value = false
  }
}

function scan() {
  uni.scanCode({
    success: res => {
      const code = (res.result || '').trim()
      const hit = batches.value.find(b => b.batchNo === code || b.workOrderNo === code)
      if (hit) {
        openBatch(hit)
      } else {
        uni.showToast({ title: '未匹配到批次：' + code, icon: 'none' })
      }
    }
  })
}

async function openBatch(batch) {
  uni.showLoading({ title: '建单中' })
  try {
    const data = await api.createInspection(batch.id)
    uni.navigateTo({ url: `/pages/inspect/inspect?id=${data.inspectionId}` })
  } finally {
    uni.hideLoading()
  }
}

onMounted(load)
</script>

<style>
.page { padding: 24rpx; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24rpx; }
.scan { flex: 1; background-color: #1989fa; color: #fff; border-radius: 12rpx; font-size: 32rpx; }
.link { margin-left: 24rpx; color: #1989fa; font-size: 28rpx; }
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
