<template>
  <view class="page">
    <view class="search">
      <input
        v-model="kw"
        class="input"
        placeholder="被投诉的批次号 / 工单号"
        confirm-type="search"
        @confirm="find"
      />
      <view class="scan-btn" @click="scan">扫码</view>
    </view>
    <button class="go-btn" :disabled="loading" @click="find">{{ loading ? '查询中…' : '查批次' }}</button>

    <view v-if="!batch" class="tip">
      客户打电话来说货有问题，当场就能记下来：哪一批、什么客户、什么问题。
      批次必须找对——没有批次的投诉只能算抱怨，查不到根因也追不了责任。
    </view>

    <template v-if="batch">
      <view class="card">
        <view class="row">
          <text class="batch-no">{{ batch.batchNo }}</text>
          <text class="batch-qty">{{ batch.qty }} 件</text>
        </view>
        <view class="kv"><text class="k">工单</text><text class="v">{{ batch.workOrderNo || '—' }}</text></view>
        <view class="kv"><text class="k">供应商</text><text class="v">{{ batch.supplier || '内部生产' }}</text></view>
        <view v-if="existing.length" class="exist">
          这批已经有 {{ existing.length }} 条客诉，登记前先看一眼是不是同一件事：
          <view v-for="c in existing.slice(0, 3)" :key="c.id" class="exist-row">
            {{ c.complaintNo }} · {{ c.customer }} · {{ complaintStatusText(c.status) }}
          </view>
          <view v-if="existing.length > 3" class="exist-more">还有 {{ existing.length - 3 }} 条</view>
        </view>
      </view>

      <view class="card">
        <view class="card-title">登记客诉</view>

        <view class="field">
          <text class="label">客户<text class="req">*</text></text>
          <input v-model="form.customer" class="ctl" placeholder="谁反馈的" />
        </view>

        <view class="field">
          <text class="label">反馈日期</text>
          <picker mode="date" :value="form.date" :end="today" @change="onDate">
            <view class="ctl picker">{{ form.date }}</view>
          </picker>
        </view>

        <view class="field col">
          <text class="label">问题类别</text>
          <view class="opts">
            <view
              v-for="(text, key) in COMPLAINT_CATEGORY_TEXT"
              :key="key"
              :class="['opt', form.category === key ? 'on' : '']"
              @click="form.category = key"
            >{{ text }}</view>
          </view>
        </view>

        <view class="field col">
          <text class="label">严重程度</text>
          <view class="opts">
            <view
              v-for="(text, key) in SEVERITY_TEXT"
              :key="key"
              :class="['opt', form.severity === key ? 'on' : '', key === 'HIGH' ? 'high' : '']"
              @click="form.severity = key"
            >{{ text }}</view>
          </view>
        </view>

        <view class="field">
          <text class="label">涉及数量</text>
          <input v-model="form.qty" class="ctl" type="number" placeholder="客户报不准就留空" />
        </view>

        <view class="field col">
          <text class="label">问题描述<text class="req">*</text></text>
          <textarea
            v-model="form.description"
            class="area"
            placeholder="客户原话尽量照抄：哪一批的哪几件、怎么用不了、什么时候发现的"
          />
        </view>

        <button class="submit" :disabled="saving" @click="submit">{{ saving ? '提交中…' : '登记客诉' }}</button>
        <view class="hint">根因、措施、责任方不在这里填——那要等查清楚了再关单，现在填只会填成「已处理」。</view>
      </view>

      <view v-if="last" class="card ok-card">
        <view class="ok-title">已登记 {{ last.complaintNo }}</view>
        <view class="ok-sub">{{ last.customer }} · {{ complaintCategoryText(last.category) }} · {{ severityText(last.severity) }}</view>
        <view class="ok-sub2">这批现在会被出货拦住，直到客诉关闭。</view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { api } from '../../common/api'
import {
  COMPLAINT_CATEGORY_TEXT,
  SEVERITY_TEXT,
  complaintCategoryText,
  complaintStatusText,
  severityText,
  todayLocal
} from '../../common/dict'
import { matchBatches, normalizeCode, pickBatch, scanCode } from '../../common/lookup'

const kw = ref('')
const batch = ref(null)
const existing = ref([])
const last = ref(null)
const loading = ref(false)
const saving = ref(false)

/**
 * 客诉日期不能晚于今天（后端也校验，这里让选不到就行，别让人选完才被驳回）。
 * 必须用本地日期：toISOString 是 UTC，早上 8 点前会算成昨天，今天反而选不了。
 */
const today = todayLocal()

const form = ref({
  customer: '',
  date: today,
  category: 'OTHER',
  severity: 'MEDIUM',
  qty: '',
  description: ''
})

function resetForm() {
  form.value = {
    customer: '',
    date: today,
    category: 'OTHER',
    severity: 'MEDIUM',
    qty: '',
    description: ''
  }
}

function onDate(e) {
  form.value.date = e.detail.value
}

function scan() {
  scanCode(code => {
    kw.value = code
    find()
  })
}

async function find() {
  const code = normalizeCode(kw.value)
  if (!code) {
    uni.showToast({ title: '请输入或扫码', icon: 'none' })
    return
  }
  if (loading.value) return
  loading.value = true
  last.value = null
  try {
    const hit = await pickBatch(await matchBatches(code), '没查到批次：' + code)
    if (!hit) {
      batch.value = null
      existing.value = []
      return
    }
    batch.value = hit
    resetForm()
    // 已有客诉只是提示，拉不到不该挡住登记
    try {
      existing.value = (await api.complaintsOfBatch(hit.id)) || []
    } catch (e) {
      existing.value = []
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  const customer = form.value.customer.trim()
  const description = form.value.description.trim()
  if (!customer) {
    uni.showToast({ title: '必须填客户名称', icon: 'none' })
    return
  }
  if (!description) {
    uni.showToast({ title: '必须写问题描述', icon: 'none' })
    return
  }
  // 客户报不准数量是常事，空着就行；填了就必须是个正常数字
  const qtyRaw = String(form.value.qty).trim()
  const qty = qtyRaw ? Number(qtyRaw) : null
  if (qtyRaw && (!Number.isInteger(qty) || qty <= 0)) {
    uni.showToast({ title: '涉及数量要填正整数，报不准就留空', icon: 'none' })
    return
  }

  saving.value = true
  try {
    const saved = await api.createComplaint({
      batchId: batch.value.id,
      customer,
      complaintDate: form.value.date,
      category: form.value.category,
      severity: form.value.severity,
      qty,
      description
    })
    last.value = saved
    existing.value = [...existing.value, saved]
    resetForm()
    uni.showToast({ title: '已登记 ' + saved.complaintNo, icon: 'none' })
  } catch (e) {
    // request() 已经弹过后端原话，这里不重复弹
  } finally {
    saving.value = false
  }
}
</script>

<style>
.page { padding: 24rpx 24rpx 60rpx; }

.search { display: flex; align-items: center; background: #fff; border-radius: 12rpx; padding: 0 22rpx; }
.input { flex: 1; height: 84rpx; font-size: 28rpx; }
.scan-btn { color: #1989fa; font-size: 28rpx; padding-left: 20rpx; }
.go-btn {
  margin-top: 20rpx; background: #1989fa; color: #fff;
  border-radius: 12rpx; font-size: 30rpx; line-height: 84rpx; height: 84rpx;
}
.go-btn[disabled] { background: #a8c8ee; color: #fff; }
.tip { font-size: 25rpx; color: #8a9199; line-height: 1.7; padding: 30rpx 8rpx; }

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-top: 20rpx; }
.card-title { font-size: 28rpx; font-weight: 500; margin-bottom: 16rpx; }
.row { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12rpx; }
.batch-no { font-size: 32rpx; font-weight: 600; }
.batch-qty { font-size: 24rpx; color: #8a9199; }
.kv { display: flex; font-size: 26rpx; padding: 8rpx 0; }
.k { width: 180rpx; color: #8a9199; }
.v { flex: 1; color: #303133; }

.exist {
  margin-top: 14rpx; padding: 16rpx 18rpx; border-radius: 10rpx;
  background: #fdf6ec; color: #b88230; font-size: 24rpx; line-height: 1.6;
}
.exist-row { margin-top: 8rpx; color: #8a6a2f; }
.exist-more { margin-top: 6rpx; color: #b0a074; }

.field { display: flex; align-items: center; padding: 18rpx 0; border-top: 1rpx solid #f4f5f7; }
.field:first-of-type { border-top: none; }
.field.col { display: block; }
.label { width: 170rpx; font-size: 27rpx; color: #5a6b7b; }
.field.col .label { display: block; margin-bottom: 14rpx; }
.req { color: #c45656; margin-left: 4rpx; }
.ctl { flex: 1; font-size: 28rpx; height: 60rpx; }
.picker { line-height: 60rpx; color: #303133; }
.area {
  width: 100%; height: 180rpx; font-size: 27rpx; line-height: 1.6;
  background: #f7f8fa; border-radius: 10rpx; padding: 16rpx;
}

.opts { display: flex; flex-wrap: wrap; }
.opt {
  font-size: 25rpx; padding: 10rpx 24rpx; border-radius: 8rpx; margin: 0 12rpx 12rpx 0;
  background: #f2f4f7; color: #5a6b7b;
}
.opt.on { background: #1989fa; color: #fff; }
.opt.high.on { background: #c45656; }

.submit {
  margin-top: 24rpx; background: #1989fa; color: #fff;
  border-radius: 12rpx; font-size: 30rpx; line-height: 84rpx; height: 84rpx;
}
.submit[disabled] { background: #a8c8ee; color: #fff; }
.hint { font-size: 22rpx; color: #a8abb2; line-height: 1.6; margin-top: 14rpx; }

.ok-card { border-left: 8rpx solid #c45656; }
.ok-title { font-size: 28rpx; font-weight: 600; color: #c45656; }
.ok-sub { font-size: 25rpx; color: #5a6b7b; margin-top: 8rpx; }
.ok-sub2 { font-size: 23rpx; color: #8a9199; margin-top: 6rpx; }
</style>
