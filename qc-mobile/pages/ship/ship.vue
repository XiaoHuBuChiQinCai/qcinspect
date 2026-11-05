<template>
  <view class="page">
    <view class="search">
      <input
        v-model="kw"
        class="input"
        placeholder="批次号 / 工单号"
        confirm-type="search"
        @confirm="find"
      />
      <view class="scan-btn" @click="scan">扫码</view>
    </view>
    <button class="go-btn" :disabled="loading" @click="find">{{ loading ? '查询中…' : '查批次' }}</button>

    <view v-if="!ctx" class="tip">
      发货时在手机上录一笔：客户、数量，系统自己算还能发多少。
      不合格或还有客诉没关的批次会被拦一道，确认要发就记成「例外放行」，留痕。
    </view>

    <template v-if="ctx">
      <view class="card">
        <view class="row">
          <text class="batch-no">{{ ctx.batchNo }}</text>
          <text class="batch-qty">{{ ctx.batchQty }} 件</text>
        </view>
        <view class="kv"><text class="k">已出货</text><text class="v">{{ ctx.shippedQty }} 件</text></view>
        <view class="kv">
          <text class="k">还能发</text>
          <text :class="['v', soldOut ? 'red' : 'strong']">{{ ctx.remainingQty }} 件</text>
        </view>
        <view class="kv">
          <text class="k">检验结论</text>
          <text :class="['v', ctx.result === 'FAIL' ? 'red' : ctx.result === 'PASS' ? 'green' : '']">
            {{ resultText(ctx.result) }}{{ ctx.inspectionNo ? ' · ' + ctx.inspectionNo : '' }}
          </text>
        </view>
        <view v-if="ctx.openComplaints" class="kv">
          <text class="k">未关闭客诉</text>
          <text class="v red">{{ ctx.openComplaints }} 条</text>
        </view>
        <view v-if="ctx.blocking" class="block">{{ ctx.blocking }}</view>
      </view>

      <!-- 发完了就别让人填一遍表单最后才挨一句报错 -->
      <view v-if="soldOut" class="card done">
        <view class="done-title">这批已经全部发出</view>
        <view class="done-sub">要改数量请先红冲出错的出货单，别在这里硬填。</view>
      </view>

      <view v-else class="card">
        <view class="card-title">登记出货</view>
        <view class="field">
          <text class="label">客户<text class="req">*</text></text>
          <input v-model="form.customer" class="ctl" placeholder="收件客户名称" />
        </view>
        <view class="field">
          <text class="label">数量<text class="req">*</text></text>
          <input v-model="form.qty" class="ctl" type="number" :placeholder="'最多 ' + ctx.remainingQty + ' 件'" />
        </view>
        <view class="field">
          <text class="label">备注</text>
          <input v-model="form.remark" class="ctl" placeholder="可不填" />
        </view>
        <button class="submit" :disabled="saving" @click="submit">{{ saving ? '提交中…' : '登记出货' }}</button>
      </view>

      <view v-if="last" class="card ok-card">
        <view class="ok-title">已登记 {{ last.shipmentNo }}</view>
        <view class="ok-sub">{{ last.customer }} · {{ last.qty }} 件 · {{ fmtTime(last.shippedAt) }}</view>
        <view class="ok-link" @click="openTrace">查看这个批次的完整履历 ›</view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { api } from '../../common/api'
import { resultText, fmtTime } from '../../common/dict'
import { confirm, matchBatches, normalizeCode, pickBatch, scanCode } from '../../common/lookup'

const kw = ref('')
const ctx = ref(null)
const last = ref(null)
const loading = ref(false)
const saving = ref(false)
const form = ref({ customer: '', qty: '', remark: '' })

/**
 * 「发完了」只在批次数量已知时才成立。
 * 批次数量没填（qty=0）时 remaining 也会算成 0，但那不代表发完了——
 * 那种批次后端根本不拦超发，前端要是照着 remaining 禁用，就变成一个填不进任何数字的死表单。
 */
const soldOut = computed(() => !!ctx.value && ctx.value.batchQty > 0 && ctx.value.remainingQty <= 0)

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
      ctx.value = null
      return
    }
    ctx.value = await api.shipmentContext(hit.id)
    form.value = { customer: '', qty: '', remark: '' }
  } catch (e) {
    ctx.value = null
  } finally {
    loading.value = false
  }
}

async function submit() {
  const customer = form.value.customer.trim()
  if (!customer) {
    uni.showToast({ title: '必须填客户名称', icon: 'none' })
    return
  }
  const qty = Number(form.value.qty)
  if (!Number.isInteger(qty) || qty <= 0) {
    uni.showToast({ title: '数量要填正整数', icon: 'none' })
    return
  }
  // 只在批次数量已知时才按剩余量拦：数量没填的批次后端也不拦超发，
  // 前端要是照着 remaining(=0) 拦，就变成一个填不进任何数字的死表单
  if (ctx.value.batchQty > 0 && qty > ctx.value.remainingQty) {
    uni.showToast({ title: '最多还能发 ' + ctx.value.remainingQty + ' 件', icon: 'none' })
    return
  }

  // 拦截原因后端一定会再算一遍，这里只是提前说清楚；确认后由后端打 [例外放行] 标记
  let force = false
  if (ctx.value.blocking) {
    const ok = await confirm(
      ctx.value.blocking + '。\n\n确认要发的话，这张单会记成「例外放行」，一年后也查得到。',
      '这批不该发'
    )
    if (!ok) return
    force = true
  }

  saving.value = true
  try {
    const saved = await api.createShipment({
      batchId: ctx.value.batchId,
      customer,
      qty,
      remark: form.value.remark.trim() || null,
      force
    })
    last.value = saved
    // 重拉前置信息：剩余量要当场变，否则第二笔会照着旧数字填
    ctx.value = await api.shipmentContext(ctx.value.batchId)
    form.value = { customer: '', qty: '', remark: '' }
    uni.showToast({ title: '已登记 ' + saved.shipmentNo, icon: 'none' })
  } catch (e) {
    // request() 已经弹过后端原话（超发 / 拦截原因都在里面），这里不再重复弹
  } finally {
    saving.value = false
  }
}

function openTrace() {
  uni.navigateTo({ url: '/pages/trace/trace?id=' + ctx.value.batchId })
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
.v.strong { font-weight: 600; }
.v.red { color: #c45656; }
.v.green { color: #4e9a51; }
.block {
  margin-top: 14rpx; padding: 16rpx 18rpx; border-radius: 10rpx;
  background: #fef0f0; color: #c45656; font-size: 25rpx; line-height: 1.6;
}

.field { display: flex; align-items: center; padding: 18rpx 0; border-top: 1rpx solid #f4f5f7; }
.field:first-of-type { border-top: none; }
.label { width: 150rpx; font-size: 27rpx; color: #5a6b7b; }
.req { color: #c45656; margin-left: 4rpx; }
.ctl { flex: 1; font-size: 28rpx; height: 60rpx; }
.submit {
  margin-top: 24rpx; background: #1989fa; color: #fff;
  border-radius: 12rpx; font-size: 30rpx; line-height: 84rpx; height: 84rpx;
}
.submit[disabled] { background: #a8c8ee; color: #fff; }

.done { border-left: 8rpx solid #c8cdd4; }
.done-title { font-size: 30rpx; font-weight: 500; color: #5a6b7b; }
.done-sub { font-size: 25rpx; color: #8a9199; margin-top: 10rpx; line-height: 1.6; }

.ok-card { border-left: 8rpx solid #4e9a51; }
.ok-title { font-size: 28rpx; font-weight: 600; color: #4e9a51; }
.ok-sub { font-size: 25rpx; color: #5a6b7b; margin-top: 8rpx; }
.ok-link { font-size: 25rpx; color: #1989fa; margin-top: 12rpx; }
</style>
