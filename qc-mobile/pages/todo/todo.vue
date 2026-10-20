<template>
  <view class="page">
    <view class="head">
      <text class="title">待办</text>
      <text class="link" @click="load">刷新</text>
    </view>

    <!-- 审核退回原来只躺在列表里，人不主动翻就看不见；这条横幅是主动推的那一半 -->
    <view v-if="noticeUnread" class="notice-bar" @click="goNotice">
      <text class="nb-text">有 {{ noticeUnread }} 条新通知</text>
      <text class="nb-link">查看 ›</text>
    </view>

    <view class="summary">
      <view class="stat">
        <text class="num">{{ summary.pendingReview || 0 }}</text>
        <text class="lab">待审核</text>
      </view>
      <view class="stat">
        <text class="num">{{ summary.pendingDefect || 0 }}</text>
        <text class="lab">待处置</text>
      </view>
      <view class="stat">
        <text class="num warn">{{ summary.overdueGauge || 0 }}</text>
        <text class="lab">量具超期</text>
      </view>
      <view class="stat">
        <text class="num">{{ summary.myRejected || 0 }}</text>
        <text class="lab">被退回</text>
      </view>
    </view>

    <view class="tabs">
      <view v-for="t in tabs" :key="t.key" :class="['tab', tab === t.key ? 'on' : '']" @click="switchTab(t.key)">
        {{ t.label }}<text v-if="badgeOf(t.key)" class="dot">{{ badgeOf(t.key) }}</text>
      </view>
    </view>

    <!-- ============ 待审核 ============ -->
    <view v-if="tab === 'review'">
      <view v-if="!canReview" class="banner">
        当前账号没有审核权限，这里只看不动。审核需要「审核员」或「管理员」角色。
      </view>

      <view v-for="r in reviews" :key="r.id" class="card">
        <view class="row" @click="openReport(r.id)">
          <text class="no">{{ r.inspectionNo }}</text>
          <text :class="['tag', r.result === 'PASS' ? 'ok' : 'ng']">
            {{ r.result === 'PASS' ? '批合格' : '批不合格' }}
          </text>
        </view>
        <view class="sub" @click="openReport(r.id)">
          批次 {{ r.batchNo || '-' }} · {{ r.productName || '-' }}
        </view>
        <view class="sub">
          抽样 {{ r.sampleQty }} 件 · 允收 {{ r.acceptQty }} · 合格 {{ r.passedQty }} / 不合格 {{ r.failedQty }}
        </view>
        <view class="reason" @click="openReport(r.id)">{{ r.judgeReason || '—' }}</view>
        <view class="sub">检验员 {{ r.inspectorName || r.createdBy || '-' }}</view>

        <view v-if="canReview" class="actions">
          <button class="btn ghost" size="mini" @click="reject(r)">退回</button>
          <button class="btn primary" size="mini" @click="approve(r)">审核通过</button>
        </view>
      </view>

      <view v-if="!reviews.length && !loading" class="empty">没有待审核的单</view>
    </view>

    <!-- ============ 待处置 ============ -->
    <view v-else-if="tab === 'defect'">
      <view v-for="d in defects" :key="d.id" class="card">
        <view class="row">
          <text class="no">{{ d.defectName }}</text>
          <text class="tag warn">{{ statusText(d.dispositionStatus) }}</text>
        </view>
        <view class="sub">
          {{ d.inspectionNo || '—' }} · 第 {{ d.pieceNo }} 件 · {{ d.defectCode || '' }}
        </view>
        <view class="sub">已定处置：{{ dispositionText(d.disposition) }}</view>

        <view class="actions">
          <button
            v-if="d.dispositionStatus === 'PROCESSING'"
            class="btn primary"
            size="mini"
            @click="finish(d)"
          >标记完成</button>
          <template v-else>
            <button class="btn warn" size="mini" @click="openConcession(d)">让步接收</button>
            <button class="btn primary" size="mini" @click="dispose(d)">处置</button>
          </template>
        </view>
      </view>

      <view v-if="!defects.length && !loading" class="empty">没有待处置的不良</view>
    </view>

    <!-- ============ 我的 ============ -->
    <view v-else>
      <view class="section">被审核退回（{{ mine.rejected.length }}）</view>
      <view v-for="m in mine.rejected" :key="m.id" class="card" @click="openInspect(m.id)">
        <view class="row">
          <text class="no">{{ m.inspectionNo }}</text>
          <text class="tag ng">待返工</text>
        </view>
        <view class="sub">批次 {{ m.batchNo || '-' }} · {{ m.productName || '-' }}</view>
        <view class="reason">{{ m.judgeReason || '审核退回，未写意见' }}</view>
      </view>
      <view v-if="!mine.rejected.length" class="empty small">没有被退回的单</view>

      <view class="section">我的草稿（{{ mine.drafts.length }}）</view>
      <view v-for="m in mine.drafts" :key="m.id" class="card" @click="openInspect(m.id)">
        <view class="row">
          <text class="no">{{ m.inspectionNo }}</text>
          <text class="tag">草稿</text>
        </view>
        <view class="sub">批次 {{ m.batchNo || '-' }} · {{ m.productName || '-' }} · 抽样 {{ m.sampleQty }} 件</view>
      </view>
      <view v-if="!mine.drafts.length" class="empty small">没有未提交的草稿</view>

      <view class="section">超期未校准量具（{{ gauges.length }}）</view>
      <view v-for="g in gauges" :key="g.id" class="card">
        <view class="row">
          <text class="no">{{ g.gaugeCode }} {{ g.gaugeName }}</text>
          <text class="tag ng">超期 {{ g.overdueDays }} 天</text>
        </view>
        <view class="sub">上次校准 {{ g.calibrateDate || '—' }} · 应校 {{ g.nextCalibrateDate || '—' }}</view>
      </view>
      <view v-if="!gauges.length" class="empty small">量具都在有效期内</view>
    </view>

    <!-- ============ 让步接收（理由 + 授权人，两个字段 modal 装不下，自己画） ============ -->
    <view v-if="conVisible" class="mask" @click="conVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">让步接收审批</text>
        <text class="dialog-tip">
          让步接收是 ISO 体系里唯一需要授权人签字放行的处置方式，理由会被审核追问。
        </text>
        <view class="dlg-row">
          <text class="dlg-lab">待处置</text>
          <text class="dlg-val">第 {{ conTarget && conTarget.pieceNo }} 件 · {{ conTarget && conTarget.defectName }}</text>
        </view>
        <textarea
          v-model="conReason"
          class="ta"
          :maxlength="500"
          placeholder="如：该尺寸超差 0.02mm，客户已确认不影响装配"
        />
        <input v-model="conApprover" class="ipt" placeholder="授权人（品质经理姓名）" />
        <view class="dialog-actions">
          <button class="btn ghost" size="mini" @click="conVisible = false">取消</button>
          <button class="btn primary" size="mini" @click="doConcession">确认让步</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api, getToken, getUser } from '../../common/api'

const tabs = [
  { key: 'review', label: '待审' },
  { key: 'defect', label: '待处置' },
  { key: 'mine', label: '我的' }
]

const summary = ref({})
const reviews = ref([])
const defects = ref([])
const mine = ref({ drafts: [], rejected: [] })
const gauges = ref([])
const noticeUnread = ref(0)
const tab = ref('review')
const loading = ref(false)

const role = computed(() => (getUser() || {}).role)
// 后端 @PreAuthorize 是真正的边界，这里只决定按钮显不显示
const canReview = computed(() => role.value === 'ADMIN' || role.value === 'REVIEWER')

function badgeOf(key) {
  if (key === 'review') return summary.value.pendingReview || 0
  if (key === 'defect') return summary.value.pendingDefect || 0
  return 0
}

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loading.value = true
  try {
    summary.value = await api.todoSummary()
    reviews.value = await api.todoReviews()
    defects.value = await api.todoDefects()
    const m = await api.todoMine()
    mine.value = { drafts: m.drafts || [], rejected: m.rejected || [] }
    gauges.value = await api.todoGauges()
    const ns = await api.noticeSummary()
    noticeUnread.value = ns.unread || 0
  } finally {
    loading.value = false
  }
}

function goNotice() {
  uni.navigateTo({ url: '/pages/notice/notice' })
}

function switchTab(k) {
  tab.value = k
}

function openReport(id) {
  uni.navigateTo({ url: `/pages/report/report?id=${id}` })
}

function openInspect(id) {
  uni.navigateTo({ url: `/pages/inspect/inspect?id=${id}` })
}

function approve(r) {
  uni.showModal({
    title: '审核通过',
    content: `${r.inspectionNo} 将归档为质量记录，之后不可修改。确定？`,
    success: async res => {
      if (!res.confirm) return
      await api.review(r.id, true, '')
      uni.showToast({ title: '已归档', icon: 'success' })
      load()
    }
  })
}

function reject(r) {
  uni.showModal({
    title: '退回修改',
    editable: true,
    placeholderText: '写清楚哪里不合格，检验员按这句话改',
    success: async res => {
      if (!res.confirm) return
      const comment = (res.content || '').trim()
      if (!comment) {
        uni.showToast({ title: '退回必须写意见', icon: 'none' })
        return
      }
      await api.review(r.id, false, comment)
      uni.showToast({ title: '已退回', icon: 'none' })
      load()
    }
  })
}

const DISPOSITIONS = [
  { label: '返工（转处理中）', value: 'REWORK', status: 'PROCESSING' },
  { label: '报废（直接完成）', value: 'SCRAP', status: 'DONE' },
  { label: '退货（转处理中）', value: 'RETURN', status: 'PROCESSING' }
]

function dispose(d) {
  uni.showActionSheet({
    itemList: DISPOSITIONS.map(x => x.label),
    success: async res => {
      const hit = DISPOSITIONS[res.tapIndex]
      if (!hit) return
      await api.dispose(d.id, hit.value, hit.status, '')
      uni.showToast({ title: '已登记处置', icon: 'success' })
      load()
    }
  })
}

function finish(d) {
  const disposition = d.disposition || 'REWORK'
  uni.showModal({
    title: '标记完成',
    content: disposition === 'REWORK'
      ? '返工完成后必须挂一张复检单才能标完成。手机上先标完成会被拦住，请回 PC 端挂复检单。'
      : '确认这批不良已处置完毕？',
    success: async res => {
      if (!res.confirm || disposition === 'REWORK') return
      await api.dispose(d.id, disposition, 'DONE', '')
      uni.showToast({ title: '已完成', icon: 'success' })
      load()
    }
  })
}

// 让步接收走单独接口：后端 dispose 遇到 CONCESSION 会直接拒绝，
// 因为这条路径必须理由和授权人同时存在，不能用「处置」的口子绕过去
const conVisible = ref(false)
const conReason = ref('')
const conApprover = ref('')
const conTarget = ref(null)

function openConcession(d) {
  conTarget.value = d
  conReason.value = ''
  conApprover.value = ''
  conVisible.value = true
}

async function doConcession() {
  const reason = conReason.value.trim()
  const approver = conApprover.value.trim()
  if (!reason || !approver) {
    uni.showToast({ title: '理由和授权人都是必填', icon: 'none' })
    return
  }
  await api.concession(conTarget.value.id, reason, approver)
  conVisible.value = false
  uni.showToast({ title: '已记录让步', icon: 'success' })
  load()
}

function statusText(s) {
  return { PENDING: '待处理', PROCESSING: '处理中', DONE: '已完成', WAIVED: '已让步' }[s] || s || ''
}

function dispositionText(d) {
  return { REWORK: '返工', SCRAP: '报废', CONCESSION: '让步接收', RETURN: '退货' }[d] || '未定'
}

onShow(load)
</script>

<style>
.page { padding: 24rpx 24rpx 60rpx; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.title { font-size: 34rpx; font-weight: 500; }
.link { color: #1989fa; font-size: 28rpx; }

.notice-bar {
  display: flex; align-items: center; justify-content: space-between;
  background: #eef5ff; border: 1rpx solid #cfe1ff; border-radius: 12rpx;
  padding: 18rpx 22rpx; margin-bottom: 20rpx;
}
.nb-text { font-size: 26rpx; color: #1a6fd4; }
.nb-link { font-size: 26rpx; color: #1989fa; }

.summary { display: flex; background: #fff; border-radius: 16rpx; padding: 24rpx 0; margin-bottom: 20rpx; }.stat { flex: 1; text-align: center; }
.num { display: block; font-size: 40rpx; font-weight: 600; color: #303133; }
.num.warn { color: #e6a23c; }
.lab { font-size: 22rpx; color: #8a9199; }

.tabs { display: flex; gap: 12rpx; margin-bottom: 20rpx; }
.tab {
  flex: 1; text-align: center; padding: 14rpx 0; border-radius: 10rpx;
  background: #eef2f7; color: #5a6b7b; font-size: 27rpx;
}
.tab.on { background: #1989fa; color: #fff; }
.dot {
  display: inline-block; min-width: 30rpx; margin-left: 8rpx; padding: 0 8rpx;
  background: #f56c6c; color: #fff; border-radius: 20rpx; font-size: 20rpx;
}

.banner {
  background: #fdf6ec; color: #b88230; font-size: 24rpx;
  padding: 18rpx 22rpx; border-radius: 10rpx; margin-bottom: 16rpx; line-height: 1.5;
}

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.row { display: flex; align-items: center; justify-content: space-between; }
.no { font-size: 30rpx; font-weight: 500; }
.sub { margin-top: 10rpx; font-size: 25rpx; color: #8a9199; }
.reason { margin-top: 10rpx; font-size: 24rpx; color: #5a6b7b; line-height: 1.5; }

.tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; background: #eef2f7; color: #5a6b7b; }
.tag.ok { background: #f0f9eb; color: #4e9a51; }
.tag.ng { background: #fef0f0; color: #c45656; }
.tag.warn { background: #fdf6ec; color: #b88230; }

.actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 20rpx; }
.btn { margin: 0; border-radius: 10rpx; font-size: 26rpx; }
.btn.primary { background: #1989fa; color: #fff; }
.btn.warn { background: #fdf6ec; color: #b88230; border: 1rpx solid #f0d9b5; }
.btn.ghost { background: #fff; color: #c45656; border: 1rpx solid #f3c4c4; }

.section { font-size: 26rpx; color: #5a6b7b; margin: 24rpx 0 14rpx; }
.empty { text-align: center; color: #a8abb2; padding: 100rpx 0; font-size: 26rpx; }
.empty.small { padding: 40rpx 0; }

/* ---- 让步接收弹层 ---- */
.mask {
  position: fixed; left: 0; top: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, .45); display: flex; align-items: center; justify-content: center; z-index: 99;
}
.dialog { width: 620rpx; background: #fff; border-radius: 20rpx; padding: 32rpx; }
.dialog-title { font-size: 32rpx; font-weight: 600; color: #303133; }
.dialog-tip { display: block; margin-top: 12rpx; font-size: 23rpx; color: #b88230; line-height: 1.5; }
.dlg-row { display: flex; align-items: center; gap: 12rpx; margin-top: 20rpx; }
.dlg-lab { font-size: 24rpx; color: #8a9199; }
.dlg-val { font-size: 25rpx; color: #303133; }
.ta {
  width: 100%; height: 160rpx; margin-top: 20rpx; padding: 16rpx;
  box-sizing: border-box; background: #f6f8fa; border-radius: 10rpx;
  font-size: 26rpx; line-height: 1.5;
}
.ipt {
  width: 100%; height: 76rpx; margin-top: 16rpx; padding: 0 16rpx;
  box-sizing: border-box; background: #f6f8fa; border-radius: 10rpx; font-size: 26rpx;
}
.dialog-actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 26rpx; }
</style>
