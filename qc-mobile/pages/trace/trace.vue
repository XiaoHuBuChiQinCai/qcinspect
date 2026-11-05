<template>
  <view class="page">
    <view class="search">
      <input
        v-model="kw"
        class="input"
        placeholder="批次号 / 工单号 / 出货单号 / 检验单号"
        confirm-type="search"
        @confirm="go"
      />
      <view class="scan-btn" @click="scan">扫码</view>
    </view>
    <button class="go-btn" :disabled="loading" @click="go">{{ loading ? '查询中…' : '追溯' }}</button>

    <view v-if="!data" class="tip">
      扫工件或流转卡上的码，能看到：谁验的、按哪版标准、实测值多少、
      不良怎么处置的、货发给了谁、有没有客诉。
    </view>

    <template v-if="data">
      <!-- 结论先行：现场拿手机查，第一个问题永远是「这批到底合不合格」 -->
      <view :class="['card', 'verdict', verdictClass]">
        <view class="v-head">
          <text class="v-title">{{ verdictText }}</text>
          <text class="v-sub">{{ batch.batchNo }} · {{ batch.qty }} 件</text>
        </view>
        <view class="v-reason">{{ verdictReason }}</view>
        <view class="v-chips">
          <text class="chip">{{ batchStatusText(batch.status) }}</text>
          <text v-if="openDefects" class="chip red">未闭环不良 {{ openDefects }} 条</text>
          <text v-if="openComplaints" class="chip red">客诉 {{ openComplaints }} 条未关闭</text>
          <text v-if="!shipments.length" class="chip warn">还没出货</text>
        </view>
      </view>

      <view class="card">
        <view class="card-title">批次与产品</view>
        <view class="kv"><text class="k">批次号</text><text class="v">{{ batch.batchNo || '—' }}</text></view>
        <view class="kv"><text class="k">工单号</text><text class="v">{{ batch.workOrderNo || '—' }}</text></view>
        <view class="kv"><text class="k">产品</text><text class="v">{{ product.productCode || '' }} {{ product.productName || '—' }}</text></view>
        <view class="kv"><text class="k">规格</text><text class="v">{{ product.spec || '—' }}</text></view>
        <view class="kv"><text class="k">供应商</text><text class="v">{{ batch.supplier || '内部生产' }}</text></view>
        <view class="kv"><text class="k">原料批号</text><text class="v">{{ batch.materialBatchNo || '—' }}</text></view>
        <view class="kv"><text class="k">到货时间</text><text class="v">{{ fmtTime(batch.arrivedAt || batch.createdAt) }}</text></view>
      </view>

      <view class="card">
        <view class="card-title">检验记录（{{ inspections.length }} 张）</view>
        <view v-if="!inspections.length" class="none">这个批次还没开过检验单</view>
        <view
          v-for="item in inspections"
          :key="item.inspection.id"
          class="insp"
          @click="openReport(item.inspection.id)"
        >
          <view class="insp-head">
            <text class="insp-no">{{ item.inspection.inspectionNo }}</text>
            <text :class="['tag', item.inspection.result === 'PASS' ? 'ok' : item.inspection.result === 'FAIL' ? 'ng' : '']">
              {{ resultText(item.inspection.result) }}
            </text>
          </view>
          <view class="insp-sub">
            {{ item.inspection.inspectorName || '—' }} · {{ fmtTime(item.inspection.inspectedAt || item.inspection.createdAt) }}
            · 标准 v{{ item.inspection.standardVersion }}
          </view>
          <view class="insp-sub">
            抽样 {{ item.inspection.sampleQty }} · 合格 {{ item.inspection.passedQty }} · 不合格 {{ item.inspection.failedQty }}
          </view>
          <view v-if="item.defects && item.defects.length" class="insp-defect">
            <view v-for="d in item.defects.slice(0, 3)" :key="d.id" class="defect-row">
              <text class="d-piece">第 {{ d.pieceNo }} 件</text>
              <text class="d-name">{{ d.defectName }}</text>
              <text class="d-status">{{ dispositionStatusText(d.dispositionStatus) }}</text>
            </view>
            <view v-if="item.defects.length > 3" class="d-more">还有 {{ item.defects.length - 3 }} 条不良，点进去看</view>
          </view>
          <view class="insp-more">查看完整报告 ›</view>
        </view>
      </view>

      <view v-if="complaints.length" class="card">
        <view class="card-title">客户投诉（{{ complaints.length }} 条）</view>
        <view v-for="c in complaints" :key="c.id" class="complaint">
          <view class="c-head">
            <text class="c-no">{{ c.complaintNo }}</text>
            <text :class="['tag', c.status === 'CLOSED' ? 'ok' : 'ng']">
              {{ c.status === 'CLOSED' ? '已关闭' : '未关闭' }}
            </text>
          </view>
          <view class="c-sub">{{ c.customer }} · {{ fmtTime(c.complaintDate) }} · {{ severityText(c.severity) }}</view>
          <view class="c-desc">{{ c.description }}</view>
          <view v-if="c.rootCause" class="c-root">根因：{{ c.rootCause }}</view>
        </view>
      </view>

      <view class="card">
        <view class="card-title">出货记录（{{ shipments.length }} 条）</view>
        <view v-if="!shipments.length" class="none">还没出货。客诉来了就查不到货去了哪。</view>
        <view v-for="s in shipments" :key="s.id" class="ship">
          <view class="s-head">
            <text class="s-no">{{ s.shipmentNo }}</text>
            <text class="s-qty">{{ s.qty }} 件</text>
          </view>
          <view class="s-sub">{{ s.customer }} · {{ fmtTime(s.shippedAt) }}</view>
          <view v-if="s.status === 'VOID'" class="s-void">
            已作废：{{ s.voidReason || '未填理由' }}（{{ s.voidedBy || '—' }}）
          </view>
        </view>
      </view>

      <view class="foot">追溯取自建档时的标准快照，标准后续改版不影响历史判定。</view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { api } from '../../common/api'
import { batchStatusText, dispositionStatusText, fmtTime, resultText } from '../../common/dict'

const kw = ref('')
const data = ref(null)
const loading = ref(false)

const batch = computed(() => data.value?.batch || {})
const product = computed(() => data.value?.product || {})
const inspections = computed(() => data.value?.inspections || [])
const shipments = computed(() => data.value?.shipments || [])
const complaints = computed(() => data.value?.complaints || [])
const openComplaints = computed(() => data.value?.openComplaints || 0)

/**
 * 后端按 createdAt 倒序给，第一张有结论的就是最新的。
 * NA 是作废，不能当成结论展示——作废单说「不合格」比没有结论更误导。
 */
const latest = computed(
  () => inspections.value.find(i => i.inspection && (i.inspection.result === 'PASS' || i.inspection.result === 'FAIL')) || null
)

const openDefects = computed(() =>
  inspections.value.reduce((sum, i) => sum + (Number(i.openDefects) || 0), 0)
)

const verdictText = computed(() => {
  if (!inspections.value.length) return '还没检验'
  if (!latest.value) return '未判定'
  return latest.value.inspection.result === 'PASS' ? '批合格' : '批不合格'
})

const verdictClass = computed(() => {
  if (!latest.value) return 'gray'
  return latest.value.inspection.result === 'PASS' ? 'ok' : 'ng'
})

const verdictReason = computed(() => {
  if (!inspections.value.length) return '这个批次还没有检验单，不能出货也没法追溯判定。'
  if (!latest.value) return '有检验单，但都还没有结论（草稿或已作废）。'
  return latest.value.inspection.judgeReason || '—'
})

function severityText(s) {
  return { HIGH: '严重', MEDIUM: '一般', LOW: '轻微' }[s] || s || '—'
}

function go() {
  resolve(kw.value)
}

function scan() {
  uni.scanCode({
    success: res => {
      kw.value = (res.result || '').trim()
      resolve(kw.value)
    },
    fail: err => {
      const m = (err && err.errMsg) || ''
      // H5 走 http 时 uni.scanCode 直接不可用（要求安全上下文）。
      // 用户取消扫码的 errMsg 是 "scanCode:fail cancel"，不该弹这个提示。
      if (/not support|不支持|无法使用/i.test(m)) {
        uni.showToast({ title: '当前环境不支持扫码，请手输单号', icon: 'none' })
      }
    }
  })
}

/**
 * 一个输入框认四种号。
 *
 * 现场手上拿到的可能是批次流转卡、出货单、或者一张检验单，
 * 让人先想「这是哪种号、该点哪个按钮」纯属添乱。
 *
 * 顺序很重要：**先按批次号/工单号精确比，再按前缀分流**。
 * 批次号是人工录入的，没有强制格式，真有人写成 "QC-2026-001"，
 * 先看前缀就会把批次当检验单号，直接跳到一个不存在的报告页。
 */
async function resolve(raw) {
  const code = String(raw || '').trim().toUpperCase().replace(/\s+/g, '')
  if (!code) {
    uni.showToast({ title: '请输入或扫码', icon: 'none' })
    return
  }
  if (loading.value) return
  loading.value = true
  data.value = null
  try {
    // keyword 是模糊匹配，拿到结果必须自己再精确比一次：
    // 搜 "B001" 会把 B0011、B0012 一起带出来，点错就查到别人的批次。
    const list = await api.batchByKeyword(code)
    const exact = (list || []).filter(b => b.batchNo === code || b.workOrderNo === code)
    if (exact.length === 1) {
      data.value = await api.traceByBatch(exact[0].id)
      return
    }
    if (exact.length > 1) {
      // 工单号可能挂多个批次（分批做），让人自己选，别替他猜
      pickBatch(exact)
      return
    }
    if (code.startsWith('SH')) {
      data.value = await api.traceByShipment(code)
      return
    }
    if (code.startsWith('QC')) {
      await openInspectionByNo(code)
      return
    }
    uni.showToast({ title: '没查到：' + code, icon: 'none' })
  } catch (e) {
    data.value = null
  } finally {
    loading.value = false
  }
}

/** 工单号可能挂多个批次（分批做），让人自己选，别替他猜 */
function pickBatch(list) {
  const top = list.slice(0, 6)
  uni.showActionSheet({
    itemList: top.map(b => `${b.batchNo}（${b.qty} 件）`),
    success: async res => {
      loading.value = true
      try {
        data.value = await api.traceByBatch(top[res.tapIndex].id)
      } finally {
        loading.value = false
      }
    }
  })
}

/** 扫到检验单号时，用户要的是那张报告，不是它的批次 */
async function openInspectionByNo(no) {
  const page = await api.inspectionSearch(no)
  const hit = ((page && page.records) || []).find(r => r.inspectionNo === no)
  if (!hit) {
    data.value = null
    uni.showToast({ title: '没找到检验单：' + no, icon: 'none' })
    return
  }
  uni.navigateTo({ url: '/pages/report/report?id=' + hit.id })
}

function openReport(id) {
  uni.navigateTo({ url: '/pages/report/report?id=' + id })
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

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; margin-top: 20rpx; }
.card-title { font-size: 28rpx; font-weight: 500; margin-bottom: 16rpx; }
.kv { display: flex; font-size: 26rpx; padding: 8rpx 0; }
.k { width: 150rpx; color: #8a9199; }
.v { flex: 1; color: #303133; }
.none { font-size: 25rpx; color: #a8abb2; line-height: 1.6; }

.verdict { border-left: 8rpx solid #ddd; }
.verdict.ok { border-left-color: #4e9a51; }
.verdict.ng { border-left-color: #c45656; }
.verdict.gray { border-left-color: #c8cdd4; }
.v-head { display: flex; align-items: baseline; justify-content: space-between; }
.v-title { font-size: 34rpx; font-weight: 600; }
.verdict.ok .v-title { color: #4e9a51; }
.verdict.ng .v-title { color: #c45656; }
.verdict.gray .v-title { color: #8a9199; }
.v-sub { font-size: 24rpx; color: #8a9199; }
.v-reason { font-size: 26rpx; color: #303133; line-height: 1.6; margin-top: 12rpx; }
.v-chips { display: flex; flex-wrap: wrap; margin-top: 12rpx; }
.chip {
  font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; margin-right: 10rpx; margin-top: 8rpx;
  background: #eef2f7; color: #5a6b7b;
}
.chip.red { background: #fef0f0; color: #c45656; }
.chip.warn { background: #fdf6ec; color: #b88230; }

.insp { padding: 18rpx 0; border-top: 1rpx solid #f4f5f7; }
.insp:first-of-type { border-top: none; }
.insp-head { display: flex; align-items: center; justify-content: space-between; }
.insp-no { font-size: 28rpx; font-weight: 500; }
.tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; background: #eef2f7; color: #5a6b7b; }
.tag.ok { background: #f0f9eb; color: #4e9a51; }
.tag.ng { background: #fef0f0; color: #c45656; }
.insp-sub { font-size: 24rpx; color: #8a9199; margin-top: 8rpx; line-height: 1.5; }
.insp-defect { margin-top: 12rpx; background: #fbfcfd; border-radius: 10rpx; padding: 10rpx 14rpx; }
.defect-row { display: flex; align-items: center; font-size: 24rpx; padding: 6rpx 0; }
.d-piece { width: 130rpx; color: #8a9199; }
.d-name { flex: 1; color: #303133; }
.d-status { color: #b88230; }
.d-more { font-size: 23rpx; color: #a8abb2; padding-top: 6rpx; }
.insp-more { font-size: 24rpx; color: #1989fa; margin-top: 12rpx; text-align: right; }

.complaint { padding: 16rpx 0; border-top: 1rpx solid #f4f5f7; }
.complaint:first-of-type { border-top: none; }
.c-head { display: flex; align-items: center; justify-content: space-between; }
.c-no { font-size: 27rpx; font-weight: 500; }
.c-sub { font-size: 23rpx; color: #8a9199; margin-top: 6rpx; }
.c-desc { font-size: 25rpx; color: #303133; margin-top: 8rpx; line-height: 1.6; }
.c-root { font-size: 23rpx; color: #5a6b7b; margin-top: 6rpx; line-height: 1.5; }

.ship { padding: 16rpx 0; border-top: 1rpx solid #f4f5f7; }
.ship:first-of-type { border-top: none; }
.s-head { display: flex; align-items: center; justify-content: space-between; }
.s-no { font-size: 27rpx; font-weight: 500; }
.s-qty { font-size: 25rpx; color: #5a6b7b; }
.s-sub { font-size: 23rpx; color: #8a9199; margin-top: 6rpx; }
.s-void { font-size: 23rpx; color: #c45656; margin-top: 6rpx; line-height: 1.5; }

.foot { font-size: 22rpx; color: #b0b6bd; text-align: center; line-height: 1.6; margin-top: 20rpx; }
</style>
