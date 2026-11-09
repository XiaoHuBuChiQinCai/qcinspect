<template>
  <view class="page">
    <view class="head">
      <text class="title">返修工单</text>
      <text class="link" @click="load">刷新</text>
    </view>

    <view class="summary">
      <view class="stat">
        <text class="num">{{ counts.open }}</text>
        <text class="lab">待返修</text>
      </view>
      <view class="stat">
        <text class="num">{{ counts.doing }}</text>
        <text class="lab">返修中</text>
      </view>
      <view class="stat">
        <text class="num warn">{{ counts.overdue }}</text>
        <text class="lab">已超期</text>
      </view>
    </view>

    <view v-if="!rows.length && !loading" class="empty">
      没有在修的返修票。
      返修票在「不良处置」里开，开完这里就能看到。
    </view>

    <view v-for="r in rows" :key="r.id" :class="['card', r.overdue ? 'late' : '']">
      <view class="row">
        <text class="no">{{ r.reworkNo }}</text>
        <text :class="['tag', r.overdue ? 'warn' : r.status === 'DOING' ? 'doing' : '']">
          {{ r.overdue ? `超期 ${r.overdueDays} 天` : statusText(r.status) }}
        </text>
      </view>

      <view class="name">{{ r.defectName }}</view>
      <view class="sub">
        {{ r.productName || '—' }}<text v-if="r.batchNo"> · 批次 {{ r.batchNo }}</text>
      </view>
      <view class="sub">
        送修 {{ r.qty }} 件 · 返修人 {{ r.assignee || '未指派' }}
        <text v-if="r.dueDate"> · 要求 {{ r.dueDate }} 完成</text>
      </view>
      <view v-if="r.requirement" class="req">要求：{{ r.requirement }}</view>

      <view class="actions">
        <button v-if="r.status === 'OPEN'" class="btn plain" size="mini" @click="start(r)">开工</button>
        <button class="btn primary" size="mini" @click="openFinish(r)">完工报数</button>
      </view>
    </view>

    <!-- 完工报数：两个数字必须对上送修数量 -->
    <view v-if="dlgVisible" class="mask" @click="dlgVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">完工 {{ target && target.reworkNo }}</text>
        <text class="dialog-tip">
          送修 {{ target && target.qty }} 件。修好的和修废的都要报，
          加起来必须正好等于送修数——少一件就是有一件没交代去向。
        </text>

        <view class="qty-row">
          <text class="dlg-lab">返好件数</text>
          <input v-model="form.reworkedQty" class="qty" type="number" placeholder="0" />
        </view>
        <view class="qty-row">
          <text class="dlg-lab">返废件数</text>
          <input v-model="form.scrappedQty" class="qty" type="number" placeholder="0" />
        </view>

        <view :class="['balance', balance.ok ? 'ok' : 'bad']">
          {{ balance.text }}
          <text v-if="hint" class="balance-hint">{{ hint }}</text>
        </view>

        <text class="dlg-lab">说明（有返废件时必填）</text>
        <textarea
          v-model="form.finishNote"
          class="ta"
          :maxlength="200"
          placeholder="返废原因、换了什么件等"
        />

        <text class="dlg-lab">{{ nextHint }}</text>

        <view class="dialog-actions">
          <button class="btn plain" size="mini" @click="dlgVisible = false">取消</button>
          <button class="btn primary" size="mini" :disabled="saving" @click="doFinish">
            {{ saving ? '提交中…' : '确认完工' }}
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api } from '../../common/api'
import { balanceText, checkFinish } from '../../common/rework.js'

const rows = ref([])
const loading = ref(false)

const dlgVisible = ref(false)
const saving = ref(false)
const target = ref(null)
const form = ref({ reworkedQty: '', scrappedQty: '', finishNote: '' })

const counts = computed(() => ({
  open: rows.value.filter(r => r.status === 'OPEN').length,
  doing: rows.value.filter(r => r.status === 'DOING').length,
  overdue: rows.value.filter(r => r.overdue).length
}))

const balance = computed(() =>
  balanceText(target.value?.qty, form.value.reworkedQty, form.value.scrappedQty)
)

const hint = computed(() => {
  const q = target.value?.qty || 0
  const t = balanceText(q, form.value.reworkedQty, form.value.scrappedQty)
  if (t.ok) return ''
  const r = Number(form.value.reworkedQty || 0)
  const s = Number(form.value.scrappedQty || 0)
  return r + s < q ? `还差 ${q - r - s} 件` : `多了 ${r + s - q} 件`
})

const nextHint = computed(() => {
  const r = Number(form.value.reworkedQty || 0)
  const s = Number(form.value.scrappedQty || 0)
  if (r > 0 && s > 0) return `下一步：自动开 ${r} 件复检单，报废的 ${s} 件计入返工损耗`
  if (r > 0) return `下一步：自动开 ${r} 件复检单（逐件全检，Ac=0）`
  if (r === 0 && s > 0) return '下一步：全部返废，不良直接改判报废，不开复检单'
  return '填完两个件数再看这里'
})

function statusText(s) {
  return { OPEN: '待返修', DOING: '返修中', DONE: '已完工' }[s] || s
}

async function load() {
  loading.value = true
  try {
    rows.value = await api.reworkPending(100)
  } catch {
    // 错误提示已在 api 层弹过，这里保持原列表不动
  } finally {
    loading.value = false
  }
}

async function start(r) {
  const res = await new Promise(resolve => {
    uni.showModal({
      title: '开工',
      content: `${r.reworkNo}（${r.defectName}，${r.qty} 件）开始返修？`,
      confirmText: '开工',
      success: resolve
    })
  })
  if (!res.confirm) return
  await api.startRework(r.id)
  uni.showToast({ title: '已开工', icon: 'success' })
  await load()
}

function openFinish(r) {
  target.value = r
  // 默认全部修好：多数返修是成功的，从 0 开始数反而容易填错
  form.value = { reworkedQty: String(r.qty), scrappedQty: '0', finishNote: '' }
  dlgVisible.value = true
}

function toInt(v) {
  const s = String(v === null || v === undefined ? '' : v).trim()
  if (s === '') return null
  const n = Number(s)
  return Number.isInteger(n) ? n : NaN
}

async function doFinish() {
  const r = toInt(form.value.reworkedQty)
  const s = toInt(form.value.scrappedQty)
  const err = checkFinish(target.value.qty, r, s)
  if (err) {
    uni.showToast({ title: err, icon: 'none' })
    return
  }
  if (s > 0 && !form.value.finishNote.trim()) {
    uni.showToast({ title: '有返废件，说明里要写清原因', icon: 'none' })
    return
  }
  saving.value = true
  try {
    const res = await api.finishRework(target.value.id, {
      reworkedQty: r,
      scrappedQty: s,
      finishNote: form.value.finishNote.trim() || undefined
    })
    dlgVisible.value = false
    uni.showModal({
      title: '已完工',
      content: res.recheckInspectionNo
        ? `复检单 ${res.recheckInspectionNo} 已生成，共 ${r} 件。回电脑上填实测值，或稍后在检验单里找它。`
        : '全部返废，这条不良已改判报废。',
      showCancel: false,
      confirmText: '知道了'
    })
    await load()
  } finally {
    saving.value = false
  }
}

onShow(load)
</script>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; padding: 24rpx; box-sizing: border-box; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.title { font-size: 34rpx; font-weight: 600; color: #303133; }
.link { font-size: 26rpx; color: #1989fa; }

.summary { display: flex; background: #fff; border-radius: 16rpx; padding: 22rpx 0; margin-bottom: 20rpx; }
.stat { flex: 1; display: flex; flex-direction: column; align-items: center; }
.num { font-size: 40rpx; font-weight: 600; color: #303133; }
.num.warn { color: #e6a23c; }
.lab { font-size: 22rpx; color: #909399; margin-top: 6rpx; }

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 18rpx; }
.card.late { border-left: 8rpx solid #f56c6c; }
.row { display: flex; align-items: center; justify-content: space-between; }
.no { font-size: 28rpx; font-weight: 600; color: #303133; }
.name { font-size: 30rpx; color: #303133; margin-top: 12rpx; }
.sub { font-size: 23rpx; color: #909399; margin-top: 8rpx; }
.req { font-size: 23rpx; color: #5a6b7b; margin-top: 10rpx; line-height: 1.5; }

.tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 20rpx; background: #f2f6fa; color: #5a6b7b; }
.tag.doing { background: #eaf3ff; color: #1a6fd4; }
.tag.warn { background: #fdf0ec; color: #d9534f; }

.actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 18rpx; }
.btn { margin: 0; border-radius: 10rpx; font-size: 25rpx; }
.btn.primary { background: #1989fa; color: #fff; }
.btn.plain { background: #f6f8fa; color: #5a6b7b; }

.empty { text-align: center; color: #909399; font-size: 26rpx; padding: 80rpx 40rpx; line-height: 1.7; }

/* ---- 完工弹层 ---- */
.mask {
  position: fixed; left: 0; top: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, .45); display: flex; align-items: center; justify-content: center; z-index: 99;
}
.dialog { width: 660rpx; background: #fff; border-radius: 20rpx; padding: 32rpx; }
.dialog-title { font-size: 32rpx; font-weight: 600; color: #303133; }
.dialog-tip { display: block; margin-top: 12rpx; font-size: 23rpx; color: #b88230; line-height: 1.6; }
.dlg-lab { display: block; margin-top: 20rpx; font-size: 24rpx; color: #5a6b7b; }
.qty-row { display: flex; align-items: center; justify-content: space-between; margin-top: 12rpx; }
.qty-row .dlg-lab { margin-top: 0; }
.qty {
  width: 220rpx; height: 68rpx; padding: 0 20rpx; box-sizing: border-box;
  background: #f6f8fa; border-radius: 10rpx; font-size: 30rpx; text-align: right;
}
.balance {
  margin-top: 20rpx; padding: 16rpx 20rpx; border-radius: 10rpx;
  font-size: 26rpx; background: #f6f8fa; color: #5a6b7b;
}
.balance.ok { background: #eef8f0; color: #2e7d32; }
.balance.bad { background: #fdf0ec; color: #c45656; }
.balance-hint { margin-left: 12rpx; font-size: 23rpx; }
.ta {
  width: 100%; height: 120rpx; margin-top: 10rpx; padding: 14rpx;
  box-sizing: border-box; background: #f6f8fa; border-radius: 10rpx;
  font-size: 26rpx; line-height: 1.5;
}
.dialog-actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 26rpx; }
</style>
