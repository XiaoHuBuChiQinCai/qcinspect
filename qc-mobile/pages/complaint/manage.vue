<template>
  <view class="page">
    <view class="head">
      <text class="title">客诉处理</text>
      <text class="link" @click="load">刷新</text>
    </view>

    <view class="tabs">
      <view :class="['tab', tab === 'open' ? 'on' : '']" @click="switchTab('open')">
        未关闭<text v-if="counts.open" class="dot">{{ counts.open }}</text>
      </view>
      <view :class="['tab', tab === 'closed' ? 'on' : '']" @click="switchTab('closed')">已关闭</view>
    </view>

    <view v-if="!canClose && tab === 'open'" class="banner">
      你可以把客诉标成「处理中」，但关单要填根因、措施和责任归属，只有审核员或管理员能关。
      关不了就别硬关——一条没查清就关掉的客诉，明年审核时谁也说不清当时怎么处理的。
    </view>

    <view v-for="c in rows" :key="c.id" class="card">
      <view class="row">
        <text class="no">{{ c.complaintNo }}</text>
        <text :class="['tag', tagClass(c)]">{{ statusText(c.status) }}</text>
      </view>
      <view class="row2">
        <text :class="['sev', severityClass(c.severity)]">{{ severityText(c.severity) }}</text>
        <text class="cat">{{ categoryText(c.category) }}</text>
        <text v-if="c.qty" class="qty">涉及 {{ c.qty }} 件</text>
      </view>
      <view class="kv"><text class="k">客户</text><text class="v">{{ c.customer || '—' }}</text></view>
      <view class="kv"><text class="k">批次</text><text class="v">{{ c.batchNo || '—' }} {{ c.productName || '' }}</text></view>
      <view class="kv"><text class="k">日期</text><text class="v">{{ c.complaintDate || '—' }}</text></view>
      <view class="desc">{{ c.description }}</view>

      <view v-if="c.status === 'CLOSED'" class="closed">
        <view class="kv"><text class="k">根本原因</text><text class="v">{{ c.rootCause || '—' }}</text></view>
        <view class="kv"><text class="k">纠正措施</text><text class="v">{{ c.action || '—' }}</text></view>
        <view class="kv"><text class="k">责任归属</text><text class="v">{{ c.responsibility || '—' }}</text></view>
        <view class="kv"><text class="k">关闭</text><text class="v">{{ c.closedBy || '—' }} · {{ fmtTime(c.closedAt) }}</text></view>
      </view>

      <view v-if="!isClosed(c.status)" class="actions">
        <button
          v-if="c.status === 'OPEN'"
          class="btn ghost"
          size="mini"
          @click="startIt(c)"
        >开始处理</button>
        <button
          v-if="canClose"
          class="btn primary"
          size="mini"
          @click="openClose(c)"
        >关单</button>
        <button class="btn plain" size="mini" @click="openTrace(c)">看批次履历</button>
      </view>
    </view>

    <view v-if="!rows.length && !loading" class="empty">
      {{ tab === 'open' ? '没有未关闭的客诉' : '还没有关闭过的客诉' }}
    </view>

    <!-- 关单：三项必填，两个 textarea 加一个输入框，modal 装不下 -->
    <view v-if="closeVisible" class="mask" @click="closeVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">关闭 {{ target && target.complaintNo }}</text>
        <text class="dialog-tip">
          根因、措施、责任归属缺一不可。写「已和客户沟通，客户接受」这种话，
          第二年没有人看得懂当时到底出了什么问题。
        </text>

        <text class="dlg-lab">根本原因</text>
        <textarea
          v-model="closeForm.rootCause"
          class="ta"
          :maxlength="500"
          placeholder="如：供应商 A 的 45# 钢棒料直径超出下偏差 0.05mm"
        />

        <text class="dlg-lab">纠正预防措施</text>
        <textarea
          v-model="closeForm.action"
          class="ta"
          :maxlength="500"
          placeholder="如：全检在库 320 件；供应商加严来料检验并提交 8D 报告"
        />

        <text class="dlg-lab">责任归属</text>
        <view class="chips">
          <text
            v-for="o in RESPONSIBILITY_OPTIONS"
            :key="o"
            :class="['chip', closeForm.responsibility === o ? 'on' : '']"
            @click="closeForm.responsibility = o"
          >{{ o }}</text>
        </view>
        <input v-model="closeForm.responsibility" class="ipt" placeholder="或自己写，如：供应商 A 的原材料" />

        <view class="dialog-actions">
          <button class="btn plain" size="mini" @click="closeVisible = false">取消</button>
          <button class="btn primary" size="mini" :disabled="saving" @click="doClose">
            {{ saving ? '提交中…' : '确认关单' }}
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { api, getToken, getUser } from '../../common/api'
import { complaintCategoryText, complaintStatusText, fmtTime, severityText } from '../../common/dict'
import {
  RESPONSIBILITY_OPTIONS,
  canCloseRole,
  closeBlocker,
  isClosed,
  sortComplaints
} from '../../common/complaint'

const rows = ref([])
const counts = ref({ open: 0 })
const tab = ref('open')
const loading = ref(false)
const saving = ref(false)

const user = getUser() || {}
// 后端 @PreAuthorize 才是真边界，这里只决定按钮显不显示
const canClose = computed(() => canCloseRole(user.role))

const statusText = complaintStatusText
const categoryText = complaintCategoryText

function tagClass(c) {
  if (c.status === 'CLOSED') return 'ok'
  return c.status === 'PROCESSING' ? 'warn' : 'ng'
}

function severityClass(s) {
  return s === 'HIGH' ? 'high' : s === 'MEDIUM' ? 'mid' : 'low'
}

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loading.value = true
  try {
    // 两个页签都拉一次：未关闭那条数要显示在页签上，光拉当前页签数不出来
    const [openPage, closedPage] = await Promise.all([
      api.complaintsPage({ open: true, size: 100 }),
      api.complaintsPage({ status: 'CLOSED', size: 100 })
    ])
    const openRows = sortComplaints(openPage.records || [])
    const closedRows = (closedPage.records || []).slice()
    counts.value = { open: openPage.total || openRows.length }
    rows.value = tab.value === 'open' ? openRows : closedRows
  } finally {
    loading.value = false
  }
}

function switchTab(k) {
  tab.value = k
  load()
}

function startIt(c) {
  uni.showModal({
    title: '开始处理',
    content: `${c.complaintNo} 标为处理中。已关闭的客诉不能再退回处理中，这条只是表明有人接手了。`,
    success: async res => {
      if (!res.confirm) return
      await api.startComplaint(c.id)
      uni.showToast({ title: '已接手', icon: 'none' })
      load()
    }
  })
}

const closeVisible = ref(false)
const target = ref(null)
const closeForm = reactive({ rootCause: '', action: '', responsibility: '' })

function openClose(c) {
  target.value = c
  // 已填过的带出来：重新打开弹窗不该把上次写的内容清掉
  closeForm.rootCause = c.rootCause || ''
  closeForm.action = c.action || ''
  closeForm.responsibility = c.responsibility || ''
  closeVisible.value = true
}

async function doClose() {
  const blocker = closeBlocker(closeForm)
  if (blocker) {
    uni.showToast({ title: blocker, icon: 'none' })
    return
  }
  if (saving.value) return
  saving.value = true
  try {
    await api.closeComplaint(target.value.id, {
      rootCause: closeForm.rootCause.trim(),
      action: closeForm.action.trim(),
      responsibility: closeForm.responsibility.trim()
    })
    closeVisible.value = false
    uni.showToast({ title: '已关单', icon: 'success' })
    await load()
  } finally {
    saving.value = false
  }
}

/** 客诉挂在批次上，看履历要跳到批次维度——单号是客诉号，追溯页认不出来 */
function openTrace(c) {
  if (!c.batchId) {
    uni.showToast({ title: '这条客诉没有关联批次', icon: 'none' })
    return
  }
  uni.navigateTo({ url: '/pages/trace/trace?id=' + c.batchId })
}

onShow(load)
</script>

<style>
.page { padding: 24rpx 24rpx 60rpx; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.title { font-size: 34rpx; font-weight: 500; }
.link { color: #1989fa; font-size: 28rpx; }

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
  padding: 18rpx 22rpx; border-radius: 10rpx; margin-bottom: 16rpx; line-height: 1.6;
}

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.row { display: flex; align-items: center; justify-content: space-between; }
.no { font-size: 30rpx; font-weight: 500; }
.row2 { display: flex; align-items: center; gap: 12rpx; margin-top: 12rpx; }
.sev { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.sev.high { background: #fef0f0; color: #c45656; }
.sev.mid { background: #fdf6ec; color: #b88230; }
.sev.low { background: #eef2f7; color: #5a6b7b; }
.cat { font-size: 24rpx; color: #5a6b7b; }
.qty { font-size: 24rpx; color: #8a9199; }

.kv { display: flex; margin-top: 10rpx; font-size: 25rpx; }
.k { width: 130rpx; color: #a0a6ad; flex-shrink: 0; }
.v { flex: 1; color: #303133; line-height: 1.5; }

.desc {
  margin-top: 14rpx; padding: 14rpx 16rpx; background: #f8fafc;
  border-radius: 10rpx; font-size: 25rpx; color: #5a6b7b; line-height: 1.6;
}
.closed {
  margin-top: 14rpx; padding-top: 14rpx; border-top: 1rpx dashed #ebeef5;
}

.tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.tag.ok { background: #f0f9eb; color: #4e9a51; }
.tag.ng { background: #fef0f0; color: #c45656; }
.tag.warn { background: #fdf6ec; color: #b88230; }

.actions { display: flex; justify-content: flex-end; gap: 14rpx; margin-top: 20rpx; flex-wrap: wrap; }
.btn { margin: 0; border-radius: 10rpx; font-size: 25rpx; }
.btn.primary { background: #1989fa; color: #fff; }
.btn.ghost { background: #fff; color: #1989fa; border: 1rpx solid #cfe1ff; }
.btn.plain { background: #f6f8fa; color: #5a6b7b; }

.empty { text-align: center; color: #a8abb2; padding: 100rpx 0; font-size: 26rpx; }

/* ---- 关单弹层 ---- */
.mask {
  position: fixed; left: 0; top: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, .45); display: flex; align-items: center; justify-content: center; z-index: 99;
}
.dialog { width: 660rpx; background: #fff; border-radius: 20rpx; padding: 32rpx; }
.dialog-title { font-size: 32rpx; font-weight: 600; color: #303133; }
.dialog-tip { display: block; margin-top: 12rpx; font-size: 23rpx; color: #b88230; line-height: 1.6; }
.dlg-lab { display: block; margin-top: 20rpx; font-size: 24rpx; color: #5a6b7b; }
.ta {
  width: 100%; height: 140rpx; margin-top: 10rpx; padding: 14rpx;
  box-sizing: border-box; background: #f6f8fa; border-radius: 10rpx;
  font-size: 26rpx; line-height: 1.5;
}
.chips { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 12rpx; }
.chip {
  font-size: 24rpx; padding: 8rpx 18rpx; border-radius: 28rpx;
  background: #f6f8fa; color: #5a6b7b;
}
.chip.on { background: #eaf3ff; color: #1a6fd4; }
.ipt {
  width: 100%; height: 76rpx; margin-top: 12rpx; padding: 0 16rpx;
  box-sizing: border-box; background: #f6f8fa; border-radius: 10rpx; font-size: 26rpx;
}
.dialog-actions { display: flex; justify-content: flex-end; gap: 16rpx; margin-top: 26rpx; }
</style>
