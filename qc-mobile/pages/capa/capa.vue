<template>
  <view class="page">
    <view class="head">
      <text class="title">纠正措施</text>
      <text class="link" @click="load">刷新</text>
    </view>

    <view class="summary">
      <view class="stat">
        <text class="num">{{ counts.mine }}</text>
        <text class="lab">我负责</text>
      </view>
      <view class="stat">
        <text class="num warn">{{ counts.overdue }}</text>
        <text class="lab">已超期</text>
      </view>
      <view class="stat">
        <text class="num">{{ counts.verifying }}</text>
        <text class="lab">待验证</text>
      </view>
    </view>

    <view v-if="canReview" class="tabs">
      <view :class="['tab', seg === 'mine' ? 'on' : '']" @click="switchSeg('mine')">我负责的</view>
      <view :class="['tab', seg === 'wait' ? 'on' : '']" @click="switchSeg('wait')">
        待验证<text v-if="counts.verifying"> · {{ counts.verifying }}</text>
      </view>
    </view>

    <view v-if="!rows.length && !loading" class="empty">
      {{ seg === 'mine'
        ? '手上没有开着的措施。措施在电脑上立，立完这里就能看到。'
        : '没有等着验证的措施。' }}
    </view>

    <view v-for="r in rows" :key="r.id" :class="['card', r.overdue ? 'late' : '']">
      <view class="row">
        <text class="no">{{ r.capaNo }}</text>
        <text :class="['tag', statusTone(r.status)]">{{ r.statusText }}</text>
      </view>

      <view class="name">{{ r.title }}</view>
      <view class="sub">{{ r.productName || '—' }}<text v-if="r.defectCode"> · {{ r.defectCode }}</text></view>

      <view v-if="r.problem" class="problem">{{ r.problem }}</view>

      <view class="sub">
        责任人 {{ r.owner || '未指派' }}
        <text v-if="r.dueDate"> · 要求 {{ r.dueDate }} 完成</text>
        <text v-if="dueHint(r)" :class="['due', r.overdue ? 'bad' : '']"> · {{ dueHint(r) }}</text>
      </view>

      <view v-if="r.rootCause" class="quote">
        <text class="quote-lab">根因</text>{{ r.rootCause }}
      </view>
      <view v-if="r.actionPlan" class="quote">
        <text class="quote-lab">措施</text>{{ r.actionPlan }}
      </view>
      <view v-if="r.verifyNote" :class="['quote', r.closeEffective ? 'ok' : 'back']">
        <text class="quote-lab">{{ r.closeEffective ? '验证' : '退回' }}</text>{{ r.verifyNote }}
      </view>

      <view class="actions">
        <button v-if="canStart(r.status)" class="btn plain" size="mini" @click="start(r)">开始处理</button>
        <button v-if="canEdit(r.status)" class="btn plain" size="mini" @click="openAnalysis(r)">
          {{ r.rootCause ? '改原因' : '填原因' }}
        </button>
        <button v-if="canSubmit(r.status)" class="btn primary" size="mini"
                :disabled="!!missingOf(r).length" @click="submit(r)">提交验证</button>
        <button v-if="r.status === 'VERIFYING' && canReview && r.owner !== me"
                class="btn primary" size="mini" @click="openVerify(r)">验证</button>
        <text v-if="r.status === 'VERIFYING' && r.owner === me" class="waiting">
          等你以外的人来判有没有用
        </text>
      </view>

      <view v-if="canSubmit(r.status) && missingOf(r).length" class="miss">
        还差：{{ missingOf(r).join('、') }}
      </view>
    </view>

    <!-- 根因与措施 -->
    <view v-if="dlg.show" class="mask" @click="dlg.show = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">{{ dlg.row && dlg.row.capaNo }}</text>
        <text class="dialog-tip">{{ dlg.row && dlg.row.problem }}</text>

        <text class="dlg-lab">根本原因</text>
        <textarea v-model="dlg.rootCause" class="ta" :maxlength="1000"
                  placeholder="为什么会发生。写「员工质量意识不足」等于没写——要写到能改得动的那一层" />

        <text class="dlg-lab">纠正措施</text>
        <textarea v-model="dlg.actionPlan" class="ta" :maxlength="1000"
                  placeholder="针对上面这个原因，具体做了什么" />

        <text class="dlg-lab">预防措施（不填也能提交，但下次换个人还会再犯）</text>
        <textarea v-model="dlg.preventPlan" class="ta short" :maxlength="1000"
                  placeholder="写进哪个作业指导书、加在哪道检验上" />

        <view class="dialog-actions">
          <button class="btn plain" size="mini" @click="dlg.show = false">取消</button>
          <button class="btn primary" size="mini" :disabled="dlg.saving" @click="doSave">
            {{ dlg.saving ? '保存中…' : '保存' }}
          </button>
        </view>
      </view>
    </view>

    <!-- 验证 -->
    <view v-if="vfy.show" class="mask" @click="vfy.show = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">验证 {{ vfy.row && vfy.row.capaNo }}</text>
        <text class="dialog-tip">{{ vfy.row && vfy.row.actionPlan }}</text>

        <view class="pick">
          <view :class="['pick-item', vfy.effective ? 'on' : '']" @click="vfy.effective = true">有效，关闭</view>
          <view :class="['pick-item', !vfy.effective ? 'on bad' : '']" @click="vfy.effective = false">
            无效，退回继续改
          </view>
        </view>

        <text class="dlg-lab">验证结论</text>
        <textarea v-model="vfy.note" class="ta" :maxlength="1000"
                  placeholder="看了什么数据、连续几个批次没再出现。「已改善」三个月后没人知道当时看了什么" />

        <text v-if="!vfy.effective" class="dlg-lab">退回后措施会回到「措施执行中」，责任人收到通知</text>

        <view class="dialog-actions">
          <button class="btn plain" size="mini" @click="vfy.show = false">取消</button>
          <button class="btn primary" size="mini" :disabled="vfy.saving" @click="doVerify">
            {{ vfy.saving ? '提交中…' : '提交判定' }}
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api, getUser } from '../../common/api'
import {
  canEdit, canStart, canSubmit, dueLabel, isOpen, missingForSubmit, statusTone
} from '../../common/capa.js'
// 「今天」只有一处出处：rework.js 里那个按本地时区拼的版本。
// 用 toISOString().slice(0,10) 是 UTC，早上八点前会取到昨天——别在这儿再写一遍
import { todayLocal } from '../../common/rework.js'

const user = ref(getUser())
/** 与后端 LoginUser.displayName() 同口径：有姓名用姓名，没有用账号 */
const me = computed(() => user.value?.realName || user.value?.username || '')
/** 验证是审核员/管理员的活。接口层还会再判一次，这里只是不显示没用的按钮 */
const canReview = computed(() => ['ADMIN', 'REVIEWER'].includes(user.value?.role))

const mine = ref([])
const waiting = ref([])
const loading = ref(false)
const seg = ref('mine')
const today = ref(todayLocal())

const rows = computed(() => (seg.value === 'mine' ? mine.value : waiting.value))

const counts = computed(() => ({
  mine: mine.value.filter(r => isOpen(r.status)).length,
  overdue: mine.value.filter(r => r.overdue).length,
  // 自己提交的也算「待验证」：对责任人来说它确实还悬着，只是判的人不是他。
  // waiting 里已经排掉了自己的，所以两边不会重复计
  verifying: waiting.value.length + mine.value.filter(r => r.status === 'VERIFYING').length
}))

const dlg = reactive({ show: false, row: null, rootCause: '', actionPlan: '', preventPlan: '', saving: false })
const vfy = reactive({ show: false, row: null, effective: true, note: '', saving: false })

function dueHint(r) {
  return dueLabel(r, today.value)
}

function missingOf(r) {
  return missingForSubmit(r)
}

function switchSeg(s) {
  seg.value = s
  load()
}

async function load() {
  loading.value = true
  today.value = todayLocal()
  try {
    // 两个都拉：「我负责的」看进度，「待验证」是审核员这一侧的动作。
    // 不在本地按 status 过滤完再翻页——那样第二页会缺
    const [m, w] = await Promise.all([
      api.capaPage({ owner: me.value, size: 50 }),
      canReview.value ? api.capaPage({ status: 'VERIFYING', size: 50 }) : Promise.resolve({ records: [] })
    ])
    mine.value = m.records || []
    // 自己负责又在自己手上待验证的，归在「我负责的」里，不在待验证里重复出现
    waiting.value = (w.records || []).filter(r => r.owner !== me.value)
  } catch {
    // 错误提示已在 api 层弹过，保持原列表不动
  } finally {
    loading.value = false
  }
}

async function start(r) {
  await confirmBox(`开始处理 ${r.capaNo}？`, '开始')
  await api.startCapa(r.id)
  uni.showToast({ title: '已开始', icon: 'success' })
  await load()
}

function openAnalysis(r) {
  Object.assign(dlg, {
    show: true,
    row: r,
    rootCause: r.rootCause || '',
    actionPlan: r.actionPlan || '',
    preventPlan: r.preventPlan || ''
  })
}

async function doSave() {
  if (!dlg.rootCause.trim() || !dlg.actionPlan.trim()) {
    uni.showToast({ title: '根本原因和纠正措施都要写', icon: 'none' })
    return
  }
  dlg.saving = true
  try {
    await api.saveCapaAnalysis(dlg.row.id, {
      rootCause: dlg.rootCause.trim(),
      actionPlan: dlg.actionPlan.trim(),
      preventPlan: dlg.preventPlan.trim() || undefined
    })
    dlg.show = false
    uni.showToast({ title: '已保存', icon: 'success' })
    await load()
  } finally {
    dlg.saving = false
  }
}

async function submit(r) {
  const miss = missingOf(r)
  if (miss.length) {
    uni.showToast({ title: `还差：${miss.join('、')}`, icon: 'none' })
    return
  }
  await confirmBox(`提交验证 ${r.capaNo}？`, '提交')
  await api.submitCapaVerify(r.id)
  uni.showToast({ title: '已提交验证', icon: 'success' })
  await load()
}

function openVerify(r) {
  Object.assign(vfy, { show: true, row: r, effective: true, note: '' })
}

async function doVerify() {
  if (!vfy.note.trim()) {
    uni.showToast({ title: '验证结论不能为空', icon: 'none' })
    return
  }
  vfy.saving = true
  try {
    await api.verifyCapa(vfy.row.id, { effective: vfy.effective, note: vfy.note.trim() })
    vfy.show = false
    uni.showToast({ title: vfy.effective ? '已关闭' : '已退回', icon: 'success' })
    await load()
  } finally {
    vfy.saving = false
  }
}

/** uni.showModal 包一层，只关心「点了确定没有」 */
function confirmBox(content, confirmText) {
  return new Promise(resolve => {
    uni.showModal({
      content,
      confirmText,
      success: res => {
        if (res.confirm) resolve(true)
      }
    })
  })
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

.tabs { display: flex; background: #fff; border-radius: 16rpx; padding: 8rpx; margin-bottom: 20rpx; }
.tab { flex: 1; text-align: center; font-size: 26rpx; color: #5a6b7b; padding: 14rpx 0; border-radius: 12rpx; }
.tab.on { background: #eaf3ff; color: #1a6fd4; font-weight: 600; }

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 18rpx; }
.card.late { border-left: 8rpx solid #f56c6c; }
.row { display: flex; align-items: center; justify-content: space-between; }
.no { font-size: 26rpx; font-weight: 600; color: #303133; }
.name { font-size: 30rpx; color: #303133; margin-top: 12rpx; line-height: 1.45; }
.sub { font-size: 23rpx; color: #909399; margin-top: 8rpx; }
.due { color: #b88230; }
.due.bad { color: #d9534f; }
.problem { font-size: 24rpx; color: #5a6b7b; margin-top: 10rpx; line-height: 1.55; }

.quote {
  margin-top: 14rpx; padding: 14rpx 16rpx; border-radius: 10rpx;
  background: #f7f9fb; font-size: 24rpx; color: #4a5b6b; line-height: 1.55;
}
.quote.ok { background: #eef8f0; color: #2e7d32; }
.quote.back { background: #fdf0ec; color: #c45656; }
.quote-lab { font-weight: 600; margin-right: 10rpx; }

.tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 20rpx; background: #f2f6fa; color: #5a6b7b; }
.tag.doing { background: #eaf3ff; color: #1a6fd4; }
.tag.wait { background: #fdf6ec; color: #b88230; }
.tag.done { background: #eef8f0; color: #2e7d32; }
.tag.drop { background: #f4f4f5; color: #909399; }

.actions { display: flex; align-items: center; justify-content: flex-end; gap: 16rpx; margin-top: 18rpx; }
.btn { margin: 0; border-radius: 10rpx; font-size: 25rpx; }
.btn.primary { background: #1989fa; color: #fff; }
.btn.plain { background: #f6f8fa; color: #5a6b7b; }
.btn.primary[disabled] { background: #cfe3f8; color: #fff; }
.waiting { font-size: 23rpx; color: #b88230; }
.miss { margin-top: 10rpx; font-size: 22rpx; color: #b88230; text-align: right; }

.empty { text-align: center; color: #909399; font-size: 26rpx; padding: 80rpx 40rpx; line-height: 1.7; }

.mask {
  position: fixed; left: 0; top: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, .45); display: flex; align-items: center; justify-content: center; z-index: 99;
}
.dialog { width: 660rpx; max-height: 86vh; overflow-y: auto; background: #fff; border-radius: 20rpx; padding: 32rpx; box-sizing: border-box; }
.dialog-title { font-size: 32rpx; font-weight: 600; color: #303133; }
.dialog-tip { display: block; margin-top: 12rpx; font-size: 23rpx; color: #5a6b7b; line-height: 1.6; }
.dlg-lab { display: block; margin-top: 20rpx; font-size: 24rpx; color: #5a6b7b; }
.ta {
  width: 100%; height: 150rpx; margin-top: 10rpx; padding: 14rpx;
  box-sizing: border-box; background: #f6f8fa; border-radius: 10rpx;
  font-size: 26rpx; line-height: 1.5;
}
.ta.short { height: 110rpx; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 26rpx; }

.pick { display: flex; gap: 16rpx; margin-top: 22rpx; }
.pick-item {
  flex: 1; text-align: center; font-size: 25rpx; color: #5a6b7b;
  padding: 18rpx 0; border-radius: 10rpx; background: #f6f8fa;
}
.pick-item.on { background: #eaf3ff; color: #1a6fd4; font-weight: 600; }
.pick-item.on.bad { background: #fdf0ec; color: #c45656; }
</style>
