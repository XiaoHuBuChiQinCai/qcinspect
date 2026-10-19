<template>
  <view class="page">
    <view class="head">
      <view>
        <text class="no">{{ ins.inspectionNo }}</text>
        <text class="sub">标准 v{{ ins.standardVersion }} · {{ ins.inspectType }}</text>
      </view>
      <text :class="['badge', finalPass ? 'ok' : 'ng']">{{ finalPass ? '合格' : '不合格' }}</text>
    </view>

    <view v-if="ins.status === 'DRAFT'" class="banner warn">
      这张单还在草稿状态，判定未生效，下面的结果仅作预览。
    </view>
    <view v-else-if="ins.status === 'VOID'" class="banner warn">
      该检验单已作废，不能作为质量记录使用。
    </view>

    <view class="card">
      <view class="kv"><text class="k">批次</text><text class="v">{{ batch.batchNo || '—' }}</text></view>
      <view class="kv"><text class="k">工单</text><text class="v">{{ batch.workOrderNo || '—' }}</text></view>
      <view class="kv"><text class="k">产品</text><text class="v">{{ product.productName || '—' }} {{ product.spec || '' }}</text></view>
      <view class="kv"><text class="k">抽样</text><text class="v">{{ samplingText }} · {{ ins.sampleQty }} 件 · 允收 {{ ins.acceptQty }}</text></view>
      <view class="kv"><text class="k">检验员</text><text class="v">{{ ins.inspectorName || '—' }}</text></view>
      <view class="kv"><text class="k">检验时间</text><text class="v">{{ fmt(ins.inspectedAt) }}</text></view>
      <view class="kv"><text class="k">审核</text><text class="v">{{ ins.reviewedBy ? ins.reviewedBy + ' · ' + fmt(ins.reviewedAt) : '未审核' }}</text></view>
    </view>

    <view :class="['card', 'result', finalPass ? 'pass' : 'fail']">
      <view class="result-head">
        <text class="result-title">{{ finalPass ? '批合格' : '批不合格' }}</text>
        <text class="result-qty">合格 {{ ins.passedQty }} 件 / 不合格 {{ ins.failedQty }} 件</text>
      </view>
      <view class="result-reason">{{ ins.judgeReason || '—' }}</view>
      <view class="result-sub">NG 行 {{ ins.ngCount }} 条<text v-if="ins.criticalNgCount > 0"> · 其中关键项 {{ ins.criticalNgCount }} 条</text></view>
    </view>

    <view class="card">
      <view class="card-title">按件结果</view>
      <view v-for="p in pieces" :key="p.pieceNo" class="piece">
        <view class="piece-head">
          <text class="piece-no">第 {{ p.pieceNo }} 件</text>
          <text :class="['tag', p.result === 'PASS' ? 'ok' : 'ng']">
            {{ p.result === 'PASS' ? '合格' : 'NG ' + p.ngCount + ' 项' }}
          </text>
        </view>
        <view v-for="row in p.rows" :key="row.id" :class="['line', row.result === 'FAIL' ? 'line-ng' : '']">
          <text class="item">{{ row.itemName }}</text>
          <text class="val">{{ valueText(row) }}</text>
          <text class="std">{{ stdText(row) }}</text>
        </view>
      </view>
    </view>

    <view v-if="defects.length" class="card">
      <view class="card-title">不良与处置</view>
      <view v-for="d in defects" :key="d.id" class="defect">
        <view class="defect-head">
          <text class="defect-name">{{ d.defectName }}</text>
          <text class="defect-status">{{ statusText(d.dispositionStatus) }}</text>
        </view>
        <view class="defect-sub">
          第 {{ d.pieceNo }} 件 · {{ dispositionText(d.disposition) }}<text v-if="d.dispositionBy"> · {{ d.dispositionBy }}</text>
        </view>
        <view v-if="d.concessionReason" class="defect-note">让步理由：{{ d.concessionReason }}</view>
      </view>
    </view>

    <view v-if="images.length" class="card">
      <view class="card-title">现场照片</view>
      <view class="photos">
        <image
          v-for="img in images"
          :key="img.id"
          :src="absUrl(img.url)"
          mode="aspectFill"
          class="photo"
          @click="preview(img)"
        />
      </view>
    </view>

    <view v-if="logs.length" class="card">
      <view class="card-title">操作留痕</view>
      <view v-for="l in logs" :key="l.id" class="log">
        <text class="log-time">{{ fmt(l.createdAt) }}</text>
        <text class="log-action">{{ actionText(l.action) }}</text>
        <text class="log-op">{{ l.operator }}</text>
        <view v-if="l.summary" class="log-sum">{{ l.summary }}</view>
      </view>
    </view>

    <view class="foot">本报告数据取自建单时的标准快照，标准后续改版不影响本单判定。</view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { absUrl, api } from '../../common/api'

const ins = ref({})
const batch = ref({})
const product = ref({})
const pieces = ref([])
const defects = ref([])
const images = ref([])
const logs = ref([])

const finalPass = computed(() => ins.value.result === 'PASS')

const samplingText = computed(
  () => ({ FULL: '全检', SAMPLE: '抽检', NONE: '免检' }[ins.value.samplingType] || ins.value.samplingType)
)

onLoad(async query => {
  const data = await api.report(query.id)
  ins.value = data.inspection || {}
  batch.value = data.batch || {}
  product.value = data.product || {}
  pieces.value = data.pieces || []
  defects.value = data.defects || []
  images.value = data.images || []
  logs.value = (data.logs || []).slice().reverse()
})

function parseValues(row) {
  if (!row.measuredValues) return []
  try {
    return JSON.parse(row.measuredValues)
  } catch (e) {
    return []
  }
}

function valueText(row) {
  if (row.valueType === 'TEXT') return row.measuredText || '—'
  const vs = parseValues(row)
  if (!vs.length) return '—'
  if (row.valueType === 'BOOL') return vs[0] === 'true' ? '有' : '无'
  if (row.valueType === 'ENUM') {
    let opts = []
    try {
      opts = row.optionsJson ? JSON.parse(row.optionsJson) : []
    } catch (e) {
      opts = []
    }
    const hit = opts.find(o => o.code === vs[0])
    return hit ? hit.name : vs[0]
  }
  return vs.join(' / ') + (row.unit ? ' ' + row.unit : '')
}

function stdText(row) {
  if (row.valueType === 'NUMBER') {
    if (row.minValue != null && row.maxValue != null) return row.minValue + ' ~ ' + row.maxValue
    if (row.minValue != null) return '≥ ' + row.minValue
    if (row.maxValue != null) return '≤ ' + row.maxValue
    return ''
  }
  if (row.valueType === 'BOOL') return row.boolPassValue ? '合格值：有' : '合格值：无'
  return ''
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

function statusText(s) {
  return { PENDING: '待处理', PROCESSING: '处理中', DONE: '已完成', WAIVED: '已让步' }[s] || s || ''
}

function dispositionText(d) {
  return { REWORK: '返工', SCRAP: '报废', CONCESSION: '让步接收', RETURN: '退货' }[d] || '未定'
}

function actionText(a) {
  return {
    CREATE: '建单',
    SUBMIT: '提交',
    REOPEN: '解锁',
    CLOSE: '归档',
    VOID: '作废',
    REVIEW: '审核通过',
    REJECT: '审核退回'
  }[a] || a
}

function preview(img) {
  uni.previewImage({ urls: images.value.map(i => absUrl(i.url)), current: absUrl(img.url) })
}
</script>

<style>
.page { padding: 24rpx 24rpx 60rpx; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.no { font-size: 34rpx; font-weight: 500; }
.sub { display: block; font-size: 24rpx; color: #8a9199; margin-top: 6rpx; }
.badge { font-size: 28rpx; padding: 8rpx 24rpx; border-radius: 10rpx; }
.badge.ok { background: #f0f9eb; color: #4e9a51; }
.badge.ng { background: #fef0f0; color: #c45656; }

.banner.warn { background: #fdf6ec; color: #b88230; font-size: 24rpx; padding: 18rpx 22rpx; border-radius: 10rpx; margin-bottom: 16rpx; line-height: 1.5; }

.card { background: #fff; border-radius: 16rpx; padding: 24rpx; margin-bottom: 20rpx; }
.card-title { font-size: 28rpx; font-weight: 500; margin-bottom: 16rpx; }
.kv { display: flex; font-size: 26rpx; padding: 8rpx 0; }
.k { width: 140rpx; color: #8a9199; }
.v { flex: 1; color: #303133; }

.result { border-left: 8rpx solid #ddd; }
.result.pass { border-left-color: #4e9a51; }
.result.fail { border-left-color: #c45656; }
.result-head { display: flex; align-items: baseline; justify-content: space-between; }
.result-title { font-size: 32rpx; font-weight: 500; }
.result.pass .result-title { color: #4e9a51; }
.result.fail .result-title { color: #c45656; }
.result-qty { font-size: 24rpx; color: #8a9199; }
.result-reason { font-size: 26rpx; color: #303133; line-height: 1.6; margin-top: 12rpx; }
.result-sub { font-size: 24rpx; color: #8a9199; margin-top: 10rpx; }

.piece { margin-bottom: 20rpx; }
.piece-head { display: flex; justify-content: space-between; align-items: center; padding: 10rpx 0; }
.piece-no { font-size: 27rpx; font-weight: 500; }
.tag { font-size: 22rpx; padding: 4rpx 14rpx; border-radius: 8rpx; }
.tag.ok { background: #f0f9eb; color: #4e9a51; }
.tag.ng { background: #fef0f0; color: #c45656; }
.line { display: flex; align-items: center; font-size: 24rpx; padding: 10rpx 0; border-top: 1rpx solid #f4f5f7; }
.line-ng { background: #fef7f7; }
.item { width: 200rpx; color: #303133; }
.val { flex: 1; color: #303133; }
.std { width: 180rpx; text-align: right; color: #a8abb2; }

.defect { padding: 14rpx 0; border-top: 1rpx solid #f4f5f7; }
.defect-head { display: flex; justify-content: space-between; }
.defect-name { font-size: 27rpx; }
.defect-status { font-size: 24rpx; color: #b88230; }
.defect-sub { font-size: 23rpx; color: #8a9199; margin-top: 6rpx; }
.defect-note { font-size: 23rpx; color: #c45656; margin-top: 6rpx; line-height: 1.5; }

.photos { display: flex; flex-wrap: wrap; gap: 14rpx; }
.photo { width: 150rpx; height: 150rpx; border-radius: 10rpx; }

.log { padding: 12rpx 0; border-top: 1rpx solid #f4f5f7; font-size: 23rpx; color: #8a9199; }
.log-time { margin-right: 12rpx; }
.log-action { color: #303133; margin-right: 12rpx; }
.log-op { color: #5a6b7b; }
.log-sum { color: #8a9199; margin-top: 6rpx; line-height: 1.5; }

.foot { font-size: 22rpx; color: #b0b6bd; text-align: center; line-height: 1.6; margin-top: 20rpx; }
</style>
