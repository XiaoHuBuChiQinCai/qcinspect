<template>
  <div>
    <!-- 筛选条 -->
    <el-card shadow="never">
      <div class="bar">
        <el-select
          v-model="picked"
          filterable
          placeholder="选择产品 / 检验项（只列有数值实测值的）"
          style="width: 380px"
        >
          <el-option-group
            v-for="g in groups"
            :key="g.productId"
            :label="g.productName || '未命名产品'"
          >
            <el-option
              v-for="it in g.items"
              :key="it.itemId"
              :label="`${it.itemCode} ${it.itemName}（${it.sampleCount} 个实测值）`"
              :value="`${it.productId}:${it.itemId}`"
            />
          </el-option-group>
        </el-select>

        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="结束"
          :clearable="false"
          style="width: 260px"
        />

        <el-button type="primary" :disabled="!picked" :loading="loading" @click="run">分析</el-button>
        <span class="tip-inline">
          只统计已提交/已归档的检验单；草稿和作废单不计入
        </span>
      </div>
    </el-card>

    <el-empty v-if="!result && !loading" description="选一个检验项，点「分析」" />

    <template v-if="result">
      <!-- 结论 -->
      <el-alert
        class="mt"
        :title="`${result.verdictText}（${verdictBasis}）`"
        :type="verdictType"
        :closable="false"
        show-icon
      >
        <template #default>
          <div class="alert-body">
            {{ result.itemCode }} {{ result.itemName }}
            <span v-if="result.unit">（单位 {{ result.unit }}）</span>
            · 区间 {{ result.from }} ~ {{ result.to }}
            · {{ result.inspectionCount }} 张单 / {{ result.n }} 个实测值
          </div>
        </template>
      </el-alert>

      <!-- 数字卡 -->
      <el-row :gutter="12" class="mt">
        <el-col v-for="k in kpis" :key="k.label" :span="4">
          <el-card shadow="never" class="kpi">
            <div class="kpi-label">{{ k.label }}</div>
            <div class="kpi-value" :class="k.cls">{{ k.value }}</div>
            <div class="kpi-hint">{{ k.hint }}</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 提示 -->
      <el-card v-if="(result.notes || []).length" shadow="never" class="mt">
        <template #header>看数之前先知道</template>
        <ul class="notes">
          <li v-for="(n, i) in result.notes" :key="i">{{ n }}</li>
        </ul>
      </el-card>

      <el-row :gutter="16" class="mt">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <span>实测值分布</span>
              <span class="sub">直方图 · 规格线按最近的分箱中心对齐</span>
            </template>
            <div ref="histEl" class="chart" />
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <span>单值控制图（I）</span>
              <span class="sub">{{ result.controlChart.individual.note }}</span>
            </template>
            <div ref="iEl" class="chart" />
            <template #footer>
              <span class="sub">{{ result.controlChart.movingRange.note }}</span>
            </template>
          </el-card>
        </el-col>
      </el-row>

      <el-card shadow="never" class="mt">
        <template #header>
          <span>移动极差图（MR）</span>
          <span class="sub">点突然跳高说明波动变大，此时 I 图可能还完全正常</span>
        </template>
        <div ref="mrEl" class="chart-sm" />
      </el-card>

      <el-card shadow="never" class="mt">
        <template #header>统计量</template>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="样本量 n">{{ result.n }}</el-descriptions-item>
          <el-descriptions-item label="检验单数">{{ result.inspectionCount }}</el-descriptions-item>
          <el-descriptions-item label="规格下限 LSL">{{ fmt(result.lsl) }}</el-descriptions-item>
          <el-descriptions-item label="规格上限 USL">{{ fmt(result.usl) }}</el-descriptions-item>
          <el-descriptions-item label="均值 X̄">{{ fmt(result.mean, 4) }}</el-descriptions-item>
          <el-descriptions-item label="整体标准差 s">{{ fmt(result.stdOverall, 6) }}</el-descriptions-item>
          <el-descriptions-item label="组内 σ̂（MR̄/1.128）">{{ fmt(result.stdWithin, 6) }}</el-descriptions-item>
          <el-descriptions-item label="移动极差均值 MR̄">{{ fmt(result.meanMr, 6) }}</el-descriptions-item>
          <el-descriptions-item label="实测最小">{{ fmt(result.dataMin, 4) }}</el-descriptions-item>
          <el-descriptions-item label="实测最大">{{ fmt(result.dataMax, 4) }}</el-descriptions-item>
          <el-descriptions-item label="超规值">{{ result.outOfSpec }}（{{ result.outOfSpecRate }}%）</el-descriptions-item>
          <el-descriptions-item label="Cp / Cpk">
            {{ fmt(result.cp) }} / {{ fmt(result.cpk) }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="tip">
          Cp/Cpk 用组内 σ̂（同一张单内部相邻测量的极差估计），只看短期抖动；
          Pp/Ppk 用整体标准差，把批与批之间的漂移也算进去。
          两者的差就是「工序稳不稳、批次之间有没有漂」。超规值一律计入，不剔除——
          剔掉再算 Cpk 等于自己给自己发合格证。
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import http from '../api/http'

// 按需引入：整包 echarts 打进产物是 1MB 起，这里只用柱状、折线、标线
echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, TitleComponent, MarkLineComponent, CanvasRenderer])

const items = ref([])
const picked = ref('')
const loading = ref(false)
const result = ref(null)

const today = new Date()
const back = new Date()
back.setDate(back.getDate() - 90)
const fmtDate = (d) => d.toISOString().slice(0, 10)
const range = ref([fmtDate(back), fmtDate(today)])

const histEl = ref(null)
const iEl = ref(null)
const mrEl = ref(null)
let charts = []

/** 按产品分组，下拉框里先选产品再选检验项，避免几十个同名「外径」混在一列 */
const groups = computed(() => {
  const map = new Map()
  for (const it of items.value) {
    const key = it.productId
    if (!map.has(key)) {
      map.set(key, { productId: key, productName: it.productName, items: [] })
    }
    map.get(key).items.push(it)
  }
  return [...map.values()]
})

const verdictType = computed(() => {
  switch (result.value?.verdict) {
    case 'EXCELLENT':
    case 'SUFFICIENT':
      return 'success'
    case 'MARGINAL':
      return 'warning'
    case 'INSUFFICIENT':
      return 'error'
    default:
      return 'info'
  }
})

/** 判定用的是 Cpk；没有 Cpk（未设规格限）时退到 Ppk，两者都没有就是无法判定 */
const verdictBasis = computed(() => {
  const r = result.value
  if (!r) return ''
  if (r.cpk != null) return `Cpk ${r.cpk}，阈值 1.67 优秀 / 1.33 充足 / 1.00 勉强`
  if (r.ppk != null) return `Cpk 无定义，按 Ppk ${r.ppk} 看`
  return '没有规格限，算不出能力指数'
})

const kpis = computed(() => {
  const r = result.value
  if (!r) return []
  const cls = (v) => {
    if (v == null) return 'na'
    if (v >= 1.67) return 'ok'
    if (v >= 1.33) return 'ok2'
    if (v >= 1.0) return 'warn'
    return 'bad'
  }
  return [
    { label: 'Cp', value: fmt(r.cp), hint: '潜在能力', cls: cls(r.cp) },
    { label: 'Cpk', value: fmt(r.cpk), hint: '实际能力', cls: cls(r.cpk) },
    { label: 'Pp', value: fmt(r.pp), hint: '整体性能', cls: cls(r.pp) },
    { label: 'Ppk', value: fmt(r.ppk), hint: '长期实际', cls: cls(r.ppk) },
    { label: '样本量', value: r.n, hint: r.n < 25 ? '不足 25，仅供参考' : '≥ 25', cls: r.n < 25 ? 'warn' : '' },
    { label: '超规值', value: r.outOfSpec, hint: `${r.outOfSpecRate}% 超出规格`, cls: r.outOfSpec > 0 ? 'bad' : 'ok' }
  ]
})

function fmt(v, scale = 3) {
  return v == null ? '—' : Number(v).toFixed(scale)
}

function dispose() {
  charts.forEach((c) => c.dispose())
  charts = []
}

const AXIS_STYLE = {
  axisLine: { lineStyle: { color: '#dcdfe6' } },
  axisLabel: { color: '#909399', fontSize: 11 },
  splitLine: { lineStyle: { color: '#f2f3f5' } }
}

const SPEC_LINE = {
  silent: true,
  symbol: 'none',
  lineStyle: { color: '#f56c6c', type: 'dashed', width: 1 },
  label: { formatter: (p) => p.name, color: '#f56c6c', fontSize: 11, position: 'insideEndTop' }
}

function drawHist() {
  const r = result.value
  if (!histEl.value || !r) return
  const bins = r.histogram.bins || []
  // 用分箱中心当类目标签，规格线吸附到最近的中心（直方图的规格线本来就是近似位置）
  const labels = bins.map((b, i) => (((Number(b.from) + Number(b.to)) / 2)).toFixed(3))
  const nearest = (v) => {
    if (v == null || !bins.length) return null
    let best = 0
    let dist = Infinity
    bins.forEach((b, i) => {
      const d = Math.abs((Number(b.from) + Number(b.to)) / 2 - Number(v))
      if (d < dist) {
        dist = d
        best = i
      }
    })
    return labels[best]
  }

  const marks = []
  const lslLabel = nearest(r.histogram.lsl)
  const uslLabel = nearest(r.histogram.usl)
  const meanLabel = nearest(r.histogram.mean)
  if (lslLabel) marks.push({ name: `LSL ${fmt(r.histogram.lsl)}`, xAxis: lslLabel })
  if (uslLabel) marks.push({ name: `USL ${fmt(r.histogram.usl)}`, xAxis: uslLabel })
  if (meanLabel) {
    marks.push({
      name: `X̄ ${fmt(r.histogram.mean)}`,
      xAxis: meanLabel,
      lineStyle: { color: '#409eff', type: 'solid', width: 1 }
    })
  }

  const chart = echarts.init(histEl.value)
  chart.setOption({
    grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps) => {
        const i = ps[0].dataIndex
        const b = bins[i]
        return `${fmt(b.from)} ~ ${fmt(b.to)}<br/>频数 ${b.count}`
      }
    },
    xAxis: { type: 'category', data: labels, ...AXIS_STYLE, axisLabel: { ...AXIS_STYLE.axisLabel, rotate: 45 } },
    yAxis: { type: 'value', name: '频数', ...AXIS_STYLE },
    series: [
      {
        type: 'bar',
        data: bins.map((b) => b.count),
        barCategoryGap: '12%',
        itemStyle: { color: '#79bbff' },
        markLine: marks.length ? { data: marks, ...SPEC_LINE } : undefined
      }
    ]
  })
  charts.push(chart)
}

function drawControl(el, chartData, color) {
  if (!el || !chartData) return
  const points = chartData.points || []
  const marks = [
    { name: 'CL ' + fmt(chartData.center), yAxis: chartData.center },
    { name: 'UCL ' + fmt(chartData.ucl), yAxis: chartData.ucl },
    { name: 'LCL ' + fmt(chartData.lcl), yAxis: chartData.lcl }
  ]
  const chart = echarts.init(el)
  chart.setOption({
    grid: { left: 8, right: 40, top: 20, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: points.map((p) => p.seq),
      ...AXIS_STYLE,
      axisLabel: { ...AXIS_STYLE.axisLabel, showMaxLabel: true }
    },
    yAxis: { type: 'value', scale: true, ...AXIS_STYLE },
    series: [
      {
        type: 'line',
        data: points.map((p) => p.value),
        symbolSize: 4,
        lineStyle: { width: 1, color },
        itemStyle: { color },
        markLine: {
          silent: true,
          symbol: 'none',
          data: marks,
          lineStyle: { type: 'dashed', width: 1, color: '#c0c4cc' },
          label: { fontSize: 10, color: '#909399', position: 'insideEndTop' }
        }
      }
    ]
  })
  charts.push(chart)
}

async function render() {
  await nextTick()
  dispose()
  if (!result.value) return
  drawHist()
  drawControl(iEl.value, result.value.controlChart.individual, '#409eff')
  drawControl(mrEl.value, result.value.controlChart.movingRange, '#e6a23c')
}

async function run() {
  if (!picked.value) return
  const [productId, itemId] = picked.value.split(':')
  loading.value = true
  result.value = null
  try {
    result.value = await http.get('/stat/capability', {
      params: { productId, itemId, from: range.value[0], to: range.value[1] }
    })
    await render()
  } catch (e) {
    ElMessage.warning(e.message || '分析失败')
  } finally {
    loading.value = false
  }
}

function onResize() {
  charts.forEach((c) => c.resize())
}

onMounted(async () => {
  items.value = await http.get('/stat/capability/items')
  // 只有一个组合时直接选中并出图，省一次点击
  if (items.value.length === 1) {
    picked.value = `${items.value[0].productId}:${items.value[0].itemId}`
    await run()
  }
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  dispose()
})
</script>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.tip-inline {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mt { margin-top: 16px; }
.sub {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}
.alert-body { font-size: 13px; line-height: 1.7; }
.chart { height: 320px; }
.chart-sm { height: 220px; }
.kpi { text-align: center; }
.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.kpi-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.4;
}
.kpi-hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.kpi-value.ok { color: var(--el-color-success); }
.kpi-value.ok2 { color: #529b2e; }
.kpi-value.warn { color: var(--el-color-warning); }
.kpi-value.bad { color: var(--el-color-danger); }
.kpi-value.na { color: var(--el-text-color-placeholder); }
.notes {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-regular);
}
.tip {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}
</style>
