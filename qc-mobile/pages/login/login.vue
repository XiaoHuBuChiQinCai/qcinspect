<template>
  <view class="page">
    <view class="brand">
      <view class="title">工件质检</view>
      <view class="sub">车间检验录入</view>
    </view>

    <view class="form">
      <view class="field">
        <text class="label">账号</text>
        <input class="input" v-model="username" placeholder="请输入账号" :disabled="loading" />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <input class="input" v-model="password" password placeholder="请输入密码" :disabled="loading" />
      </view>

      <button class="btn" type="primary" :loading="loading" :disabled="loading" @click="submit">
        登 录
      </button>
    </view>

    <view class="tip">登录状态保留在此设备，换人使用请先在首页退出</view>
  </view>
</template>

<script>
import { api, saveSession } from '../../common/api.js'

export default {
  data() {
    return {
      username: '',
      password: '',
      loading: false
    }
  },
  methods: {
    async submit() {
      if (!this.username || !this.password) {
        uni.showToast({ title: '请输入账号和密码', icon: 'none' })
        return
      }
      this.loading = true
      try {
        const data = await api.login(this.username.trim(), this.password)
        saveSession(data.token, data.user)
        uni.reLaunch({ url: '/pages/index/index' })
      } catch (e) {
        // 接口层已弹提示
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style>
.page { min-height: 100vh; padding: 90rpx 60rpx; background: #f5f6f8; box-sizing: border-box; }
.brand { text-align: center; margin-bottom: 70rpx; }
.title { font-size: 46rpx; font-weight: 600; letter-spacing: 6rpx; color: #303133; }
.sub { margin-top: 14rpx; font-size: 24rpx; color: #909399; letter-spacing: 2rpx; }
.form { background: #fff; border-radius: 16rpx; padding: 20rpx 30rpx 36rpx; }
.field { display: flex; align-items: center; border-bottom: 1rpx solid #ebeef5; padding: 26rpx 0; }
.label { width: 110rpx; font-size: 28rpx; color: #606266; }
.input { flex: 1; font-size: 30rpx; }
.btn { margin-top: 46rpx; letter-spacing: 8rpx; }
.tip { margin-top: 40rpx; text-align: center; font-size: 22rpx; color: #b1b3b8; line-height: 1.6; }
</style>
