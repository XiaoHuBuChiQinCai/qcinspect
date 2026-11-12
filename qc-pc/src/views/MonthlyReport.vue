<template>
  <div v-loading="loading" class="monthly">
    <div class="toolbar no-print">
      <el-page-header content="质量月报" @back="$router.back()" />
      <div class="spacer" />
      <el-date-picker
        v-model="month"
        type="month"
        value-format="YYYY-MM"
        placeholder="选择月份"
        :clearable="false"
        style="width: 150px"
        @change="load"
      />
      <el-button @click="shiftMonth(0)">本月</el-button>
      <el-button @click="shiftMonth(-1)">上月</el-button>
      <el-button type="primary" @click="print">打印</el-button>
    </div>

    <!-- 空月要说清楚是「没做检验」，不是「全都不合格」。这两个在报表上长得一样，
         含义差着一个数量级 -->
    <el-alert
      v-if="ready && !data.hasData"
      type="info"
      :closable="false"
      class="no-print empty-tip"
      title="这个月没有已提交的检验单，月报没有数据可汇总。草稿和已作废的单不计入月报。"
    />

    <div v-if="ready" class="sheet">
      <header class="sheet-head">
        <h1>{{ data.label }} · 质量月报</h1>
        <div class="meta">
          统计区间 {{ data.from }} 至 {{ data.to }}　·　生成于 {{ generatedAt }}
        </div>
      </header>

      <!-- ---------- 关键指标 ---------- -->
      <div class="kpis">
        <div v-for="k in kpis" :key="k.label" class="kpi" :class="k.tone">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">{{ k.value }}</div>
          <div class="kpi-sub">{{ k.sub }}</div>
        </div>
      </div>

      <!-- ---------- 结论 ---------- -->
      <h2>一、本月结论</h2>
      <ol class="narrative">
        <li v-for="(line, i) in data.narrative" :key="i">{{ line }}</li>
      </ol>

      <!-- ---------- 按检验类型 ---------- -->
      <h2>二、按检验类型</h2>
      <el-table :data="data.byType" size="small" border show-summary :summary-method="typeSummary">
        <el-table-column prop="inspectTypeName" label="检验类型" min-width="110" />
        <el-table-column prop="inspectType" label="代码" width="80" />
        <el-table-column prop="orders" label="检验单" width="80" align="right" />
        <el-table-column prop="pieceTotal" label="受检件" width="90" align="right" />
        <el-table-column prop="piecePass" label="合格件" width="90" align="right" />
        <el-table-column prop="pieceFail" label="不合格件" width="100" align="right" />
        <el-table-column label="件级合格率" width="110" align="right">
          <template #default="{ row }">{{ pct(row.yieldRate) }}</template>
        </el-table-column>
        <el-table-column label="单级合格率" width="110" align="right">
          <template #default="{ row }">{{ pct(row.orderYieldRate) }}</template>
        </el-table-column>
      </el-table>
      <div class="tip">
        抽检场景以件级合格率为准：单级看的是「整张单判没判合格」，一张单里 50 件坏 2 件仍算整单合格，
        单级合格率会虚高。
      </div>

      <!-- ---------- 主要不良 ---------- -->
      <h2>三、主要不良</h2>
      <template v-if="data.defects && data.defects.length">
        <el-table :data="data.defects" size="small" border>
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="name" label="不良项" min-width="160" />
          <el-table-column prop="cnt" label="发生次数" width="100" align="right" />
          <el-table-column prop="qty" label="不良件数" width="100" align="right" />
          <el-table-column label="占本月不良" min-width="180">
            <template #default="{ row }">
              <div class="share">
                <div class="bar"><i :style="{ width: barWidth(row.share) }" /></div>
                <span class="share-num">{{ pct(row.share) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div class="tip">
          按不良件数排序（不是按发生次数）：一个项在 50 件里各坏 1 件，和在一个项上坏了 50 件，
          后者的处理优先级更高。本月不良合计 {{ data.defectQty }} 件。
        </div>
      </template>
      <el-empty v-else description="本月没有不良记录" :image-size="56" />

      <!-- ---------- 不良处置 ---------- -->
      <h2>四、不良处置去向</h2>
      <template v-if="data.dispositions && data.dispositions.length">
        <el-table :data="data.dispositions" size="small" border>
          <el-table-column prop="name" label="处置方式" width="120" />
          <el-table-column prop="cnt" label="记录数" width="100" align="right" />
          <el-table-column prop="qty" label="件数" width="100" align="right" />
          <el-table-column label="说明" min-width="320">
            <template #default="{ row }">{{ dispositionHint(row.disposition) }}</template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="本月没有需要处置的不良" :image-size="56" />

      <!-- ---------- 供应商 ---------- -->
      <h2>五、来料供应商（本月最差 {{ data.suppliers.length }} 家）</h2>
      <template v-if="data.suppliers && data.suppliers.length">
        <el-table :data="data.suppliers" size="small" border>
          <el-table-column prop="supplier" label="供应商" min-width="180" />
          <el-table-column prop="batches" label="批次" width="80" align="right" />
          <el-table-column prop="lots" label="检验单" width="90" align="right" />
          <el-table-column prop="pieceTotal" label="受检件" width="90" align="right" />
          <el-table-column prop="piecePass" label="合格件" width="90" align="right" />
          <el-table-column label="件级合格率" width="110" align="right">
            <template #default="{ row }">
              <span :class="rateClass(row.yieldRate)">{{ pct(row.yieldRate) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="tip">按件级合格率升序，最差的排最前面。这个月没来货的供应商不出现。</div>
      </template>
      <el-empty v-else description="本月没有来料检验记录" :image-size="56" />

      <!-- ---------- 客诉与量具 ---------- -->
      <h2>六、客户投诉与计量器具</h2>
      <el-row :gutter="16">
        <el-col :span="12">
          <div class="box">
            <div class="box-title">客户投诉</div>
            <div class="pairs">
              <div><span>本月新登记</span><b>{{ data.complaints.opened }} 起</b></div>
              <div><span>其中高优先级</span><b :class="{ bad: data.complaints.high > 0 }">{{ data.complaints.high }} 起</b></div>
              <div><span>本月关闭</span><b>{{ data.complaints.closed }} 起</b></div>
              <div><span>仍未关闭（含历史）</span><b :class="{ bad: data.complaints.openNow > 0 }">{{ data.complaints.openNow }} 起</b></div>
            </div>
            <div class="box-tip">
              {{ backlogHint }}
            </div>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="box">
            <div class="box-title">计量器具（当前状态）</div>
            <div class="pairs">
              <div><span>台账总数</span><b>{{ data.gauges.total }} 把</b></div>
              <div><span>已过校准有效期</span><b :class="{ bad: data.gauges.overdue > 0 }">{{ data.gauges.overdue }} 把</b></div>
              <div><span>校准不合格停用</span><b :class="{ bad: data.gauges.suspended > 0 }">{{ data.gauges.suspended }} 把</b></div>
              <div><span>本月送校</span><b>{{ data.gauges.calibrated.total }} 次（合格 {{ data.gauges.calibrated.pass }}）</b></div>
            </div>
            <div class="box-tip">
              超期或停用的量具提交检验单时会被系统拦下，但台账本身应当月清账。
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- ---------- 过程能力 ---------- -->
      <!-- 前面六段都是滞后指标：合格率掉下来的时候，不良已经做出来、已经返工了。
           这一段是月报里唯一能提前一个月的信号——Cpk 在往下走，不良还没来 -->
      <h2>七、过程能力（月度 Cpk）</h2>
      <template v-if="cap.hasData">
        <div class="sub-title">
          本月可判 <b>{{ cap.judged }}</b> 项特性：能力不足
          <b :class="{ bad: cap.insufficient > 0 }">{{ cap.insufficient }}</b> 项、
          比上月明显下滑 <b :class="{ warn2: cap.slipping > 0 }">{{ cap.slipping }}</b> 项
          （掉 {{ slipThreshold }} 以上才计）、样本不足暂不判
          <b>{{ cap.fewSamples }}</b> 项。
        </div>
        <el-table :data="capRows" size="small" border>
          <el-table-column prop="label" label="产品 · 检验项" min-width="190" />
          <el-table-column label="样本" width="70" align="right">
            <template #default="{ row }">
              <span :class="{ bad: !row.enoughSamples }">{{ row.n }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规格" width="120">
            <template #default="{ row }">{{ specText(row) }}</template>
          </el-table-column>
          <el-table-column label="本月 Cpk" width="95" align="right">
            <template #default="{ row }">
              <span :class="cpkClass(row)">{{ cpkText(row.cpk) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="上月 Cpk" width="95" align="right">
            <template #default="{ row }">{{ cpkText(row.prevCpk) }}</template>
          </el-table-column>
          <el-table-column label="变化" width="90" align="right">
            <template #default="{ row }">
              <span :class="changeClass(row)">{{ changeText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="判定" width="110">
            <template #default="{ row }">
              <span v-if="!row.enoughSamples" class="muted">样本不足</span>
              <span v-else :class="cpkClass(row)">{{ row.verdictText }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="tip">
          只逐项看，不给平均 Cpk：不同特性的公差宽度差一个数量级，平均出来的数没有物理含义，
          而且会被公差宽、能力高的那几项拉上去，正好盖住真正在出问题的那一项。<br />
          月报的 Cpk 只取一个自然月，和 SPC 页（默认 90 天）<b>不是同一个数</b>——窗口不同、样本量不同，
          算法倒是同一套。要对外报数，去 SPC 页按客户要求的区间跑一次。<br />
          上月 Cpk 为「-」表示不可比（上月样本不足、没有数据，或这两个月之间规格限改过），不是上月很差。
        </div>
      </template>
      <div v-else class="tip">
        本月没有可评过程能力的特性。只有「设了规格上下限、并且录了实测数值」的检验项才算得出 Cpk，
        只判合格/不合格的项没有均值和标准差。
      </div>

      <!-- ---------- 纠正措施 ---------- -->
      <!-- 前面几段讲「这个月做得怎么样」，这一段讲「做错了之后改没改」。
           它是管理评审问的第二句，也是唯一能证明「不良处置」之外还有「问题纠正」的地方 -->
      <h2>八、纠正措施（CAPA）</h2>
      <el-row :gutter="16">
        <el-col :span="12">
          <div class="box">
            <div class="box-title">本月动作</div>
            <div class="pairs">
              <div><span>本月新立</span><b>{{ capa.opened }} 条</b></div>
              <div><span>本月关闭</span><b :class="{ bad: capa.opened > 0 && capa.closed === 0 }">{{ capa.closed }} 条</b></div>
              <div><span>其中判无效退回</span><b>{{ capa.rejected }} 条</b></div>
              <div><span>本月撤销</span><b>{{ capa.cancelled }} 条</b></div>
              <div><span>平均关闭用时</span><b>{{ avgCloseText }}</b></div>
            </div>
            <div class="box-tip">
              判无效退回不是坏消息——它说明验证的人在拿数据判，而不是看见「已完成」就点通过。
              这里刻意不算「验证通过率」：表里只存最后一次验证结果，算出来的数衡量的是谁更容易被劝退，
              而一旦把它挂上报表，就没人再判无效了。
            </div>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="box">
            <div class="box-title">当前状态（截至生成日）</div>
            <div class="pairs">
              <div><span>仍开着的措施</span><b>{{ capa.openNow }} 条</b></div>
              <div><span>已过要求完成日</span><b :class="{ bad: capa.overdue > 0 }">{{ capa.overdue }} 条</b></div>
              <div><span>挂得最久的一条</span><b :class="{ bad: capa.maxDaysOpen > 60 }">{{ maxDaysText }}</b></div>
            </div>
            <div class="box-tip">
              {{ capaHint }}
            </div>
          </div>
        </el-col>
      </el-row>
      <template v-if="capa.slowest && capa.slowest.length">
        <div class="sub-title">本月关闭最慢的（平均会被三五天的拉平，这几条才是没人愿意碰的）</div>
        <el-table :data="capa.slowest" size="small" border>
          <el-table-column prop="capaNo" label="单号" width="150" />
          <el-table-column prop="title" label="措施" min-width="240" />
          <el-table-column prop="owner" label="责任人" width="110" />
          <el-table-column prop="createdDay" label="立单" width="110" />
          <el-table-column prop="closedDay" label="关闭" width="110" />
          <el-table-column prop="days" label="用时" width="90" align="right">
            <template #default="{ row }">{{ row.days }} 天</template>
          </el-table-column>
        </el-table>
      </template>

      <footer class="sheet-foot">
        本报表由 QcInspect 自动汇总生成，数据口径为「已提交 / 已关闭的检验单」，作废单与草稿不计入。
        与检验单、不良台账、客诉台账的明细可逐条追溯。
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api/http'

const month = ref(lastMonth())
const data = ref({})
const loading = ref(false)
const ready = ref(false)
const generatedAt = ref('')

function lastMonth() {
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function shiftMonth(delta) {
  // delta=0 是本月，-1 是上月；不要用 month.value 自己去减，字符串减法容易减出 2026-00
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() + delta)
  month.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  load()
}

async function load() {
  loading.value = true
  try {
    data.value = await http.get('/reports/monthly', { params: { month: month.value } })
    ready.value = true
    generatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
  } finally {
    loading.value = false
  }
}

const cur = computed(() => data.value.current || {})
const prev = computed(() => data.value.previous || {})
const delta = computed(() => data.value.delta || {})

/** 环比一句话。null 一律说「无数据」，不说「持平」——没数据不等于没变化 */
function ppText(v, prevValue) {
  if (v === null || v === undefined) {
    return prevValue === null || prevValue === undefined ? '上月无数据，不计算环比' : '无可比数据'
  }
  if (v === 0) {
    return '与上月持平'
  }
  return `较上月 ${pct(prevValue)}，${v > 0 ? '上升' : '下降'} ${Math.abs(v).toFixed(1)} 个百分点`
}

const kpis = computed(() => {
  const c = cur.value
  return [
    {
      label: '检验单',
      value: c.orders ?? 0,
      sub: delta.value.orders === null || delta.value.orders === undefined
        ? '上月无数据'
        : `较上月 ${delta.value.orders >= 0 ? '+' : ''}${delta.value.orders} 张`
    },
    {
      label: '受检件数',
      value: c.pieceTotal ?? 0,
      sub: `合格 ${c.piecePass ?? 0} / 不合格 ${c.pieceFail ?? 0}`
    },
    {
      label: '件级合格率',
      value: pct(c.yieldRate),
      sub: ppText(delta.value.yieldPp, prev.value.yieldRate),
      tone: toneOf(c.yieldRate)
    },
    {
      label: 'PPM',
      value: c.ppm === null || c.ppm === undefined ? '-' : Number(c.ppm).toLocaleString('zh-CN'),
      sub: '每百万件不良数'
    },
    {
      label: '返工复检',
      value: c.recheckOrders ?? 0,
      sub: c.recheckRate === null || c.recheckRate === undefined ? '无检验单' : `占检验单 ${pct(c.recheckRate)}`
    },
    {
      label: '客诉未关闭',
      value: data.value.complaints?.openNow ?? 0,
      sub: `本月新开 ${data.value.complaints?.opened ?? 0}，关闭 ${data.value.complaints?.closed ?? 0}`,
      tone: (data.value.complaints?.openNow ?? 0) > 0 ? 'warn' : ''
    }
  ]
})

const backlogHint = computed(() => {
  const c = data.value.complaints || {}
  const d = c.backlogDelta ?? 0
  if (d > 0) {
    return `本月新开 ${c.opened} 起、关闭 ${c.closed} 起，积压增加 ${d} 起。`
  }
  if (d < 0) {
    return `本月新开 ${c.opened} 起、关闭 ${c.closed} 起，积压在消化。`
  }
  return '本月新开与关闭数量持平，积压未变化。'
})

/** 措施这一段每个数都可能缺（老数据 / 空月），统一在这里补 0，模板里不再写 ?? */
// ---------- 过程能力 ----------
const cap = computed(() => data.value.capability || {})
const capRows = computed(() => cap.value.rows || [])
/** 下滑门槛由服务端给（CapabilityNarrative.SLIP），前端只拿来上色，不再自己定一个 */
const slipThreshold = computed(() => {
  const v = cap.value.slipThreshold
  return v === null || v === undefined ? 0.3 : v
})

/**
 * Cpk 的颜色只看服务端给的 verdict。
 * 不在前端按 1.00/1.33 重新比一遍——判档只在 CapabilityMath 一处，两处阈值迟早会漂。
 */
function cpkClass(row) {
  if (!row || row.cpk === null || row.cpk === undefined) return ''
  if (row.verdict === 'INSUFFICIENT') return 'bad'
  if (row.verdict === 'MARGINAL') return 'warn2'
  return ''
}

function cpkText(v) {
  return v === null || v === undefined ? '-' : Number(v).toFixed(2)
}

/** 规格限可能是单边（只有下限或只有上限），别硬拼成「14 ~ 16」 */
function specText(row) {
  const lo = row.lsl
  const hi = row.usl
  if ((lo === null || lo === undefined) && (hi === null || hi === undefined)) return '-'
  if (lo === null || lo === undefined) return '≤ ' + hi
  if (hi === null || hi === undefined) return '≥ ' + lo
  return lo + ' ~ ' + hi
}

function changeText(row) {
  const c = row.change
  if (c === null || c === undefined) return '-'
  const v = Number(c)
  if (Math.abs(v) < 0.005) return '持平'
  return (v > 0 ? '↑ ' : '↓ ') + Math.abs(v).toFixed(2)
}

/** Cpk 掉是坏事（红），涨不标色——报表上不需要为「没退步」发奖状 */
function changeClass(row) {
  const c = row.change
  if (c === null || c === undefined) return ''
  const v = Number(c)
  if (v <= -slipThreshold.value) return 'bad'
  if (v < 0) return 'warn2'
  return ''
}

const capa = computed(() => {
  const c = data.value.capa || {}
  const n = (v) => Number(v) || 0
  return {
    opened: n(c.opened),
    closed: n(c.closed),
    rejected: n(c.rejected),
    cancelled: n(c.cancelled),
    openNow: n(c.openNow),
    overdue: n(c.overdue),
    maxDaysOpen: n(c.maxDaysOpen),
    avgCloseDays: c.avgCloseDays === null || c.avgCloseDays === undefined ? null : Number(c.avgCloseDays),
    slowest: c.slowest || []
  }
})

const avgCloseText = computed(() => {
  const v = capa.value.avgCloseDays
  // 本月一条都没关时不说「平均 0 天」——那是句假话，会让人以为措施当天就关掉了
  if (v === null) return capa.value.closed > 0 ? '-' : '本月无关闭'
  return Number.isInteger(v) ? `${v} 天` : `${v.toFixed(1)} 天`
})

const maxDaysText = computed(() => {
  // 没有开着的措施时给「—」，别在这一行写一句话——那一格对的是数字，
  // 文字说明交给下面的 capaHint，否则一行里左边两个字右边一整句，看着就错位
  if (!capa.value.openNow) return '—'
  return `${capa.value.maxDaysOpen} 天`
})

const capaHint = computed(() => {
  const c = capa.value
  if (!c.openNow) {
    return '手上没有未完成的措施。'
  }
  if (c.overdue > 0) {
    return '超期通常不是没时间，是没人再催。挂过两个月的，基本可以当成「已经放弃了但没写撤销理由」。'
  }
  if (c.maxDaysOpen > 60) {
    return '没有超期，但有一条已经挂了两个月以上——这种通常不是还在改，是该有人去问一句了。'
  }
  return '没有超期。'
})

/** 合格率分档只做视觉提示，不参与任何判定 —— 判定永远在检验单上 */
function toneOf(rate) {
  if (rate === null || rate === undefined) return ''
  if (rate < 0.9) return 'bad'
  if (rate < 0.98) return 'warn'
  return 'ok'
}

function rateClass(rate) {
  if (rate === null || rate === undefined) return ''
  if (rate < 0.9) return 'bad'
  if (rate < 0.98) return 'warn2'
  return ''
}

/** 比例 → 百分比；null 显示「-」而不是 0.0%，「没有数据」和「零合格」是两回事 */
function pct(v) {
  if (v === null || v === undefined) return '-'
  return (Number(v) * 100).toFixed(1) + '%'
}

function barWidth(share) {
  if (!share) return '0%'
  return Math.min(100, Number(share) * 100).toFixed(1) + '%'
}

function dispositionHint(d) {
  return {
    REWORK: '返工后重新检验，复检按逐件全检、Ac=0 判',
    SCRAP: '直接报废，计入质量成本',
    CONCESSION: '让步接收，需授权人签字并留理由',
    RETURN: '退回供应商，需与来料检验结果对账'
  }[d] || '—'
}

/** 合计行自己算，不让 el-table 默认把「合格率」也当数字相加 */
function typeSummary({ columns, data: rows }) {
  return columns.map((col, i) => {
    if (i === 0) return '合计'
    if (i === 1) return ''
    if (['orders', 'pieceTotal', 'piecePass', 'pieceFail'].includes(col.property)) {
      return rows.reduce((s, r) => s + Number(r[col.property] || 0), 0)
    }
    if (i === 6) {
      const total = rows.reduce((s, r) => s + Number(r.pieceTotal || 0), 0)
      const pass = rows.reduce((s, r) => s + Number(r.piecePass || 0), 0)
      return total ? pct(pass / total) : '-'
    }
    if (i === 7) {
      const total = rows.reduce((s, r) => s + Number(r.orders || 0), 0)
      const pass = rows.reduce((s, r) => s + Number(r.passOrders || 0), 0)
      return total ? pct(pass / total) : '-'
    }
    return ''
  })
}

function print() {
  window.print()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.spacer { flex: 1; }
.empty-tip { margin-bottom: 12px; }

.sheet {
  background: #fff;
  padding: 28px 32px 36px;
  border-radius: 6px;
  box-shadow: 0 0 0 1px #ebeef5;
  max-width: 1100px;
}
.sheet-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 1px;
}
.sheet-head .meta {
  font-size: 12px;
  color: #909399;
  margin-bottom: 20px;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
  margin-bottom: 22px;
}
.kpi {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fafafa;
}
.kpi-label { font-size: 12px; color: #909399; }
.kpi-value { font-size: 22px; font-weight: 600; margin: 4px 0 2px; line-height: 1.2; }
.kpi-sub { font-size: 11px; color: #a8abb2; line-height: 1.4; }
.kpi.ok .kpi-value { color: #67c23a; }
.kpi.warn .kpi-value { color: #e6a23c; }
.kpi.bad .kpi-value { color: #f56c6c; }
.kpi.warn .kpi-sub { color: #e6a23c; }

h2 {
  font-size: 15px;
  font-weight: 600;
  margin: 26px 0 10px;
  padding-left: 9px;
  border-left: 3px solid #409eff;
  line-height: 1.2;
}

.narrative {
  margin: 0;
  padding-left: 22px;
  line-height: 1.9;
  font-size: 13px;
  color: #303133;
}
.narrative li { margin-bottom: 2px; }

.share { display: flex; align-items: center; gap: 8px; }
.bar {
  flex: 1;
  height: 8px;
  background: #f0f2f5;
  border-radius: 4px;
  overflow: hidden;
}
.bar i { display: block; height: 100%; background: #f56c6c; border-radius: 4px; }
.share-num { width: 48px; text-align: right; font-size: 12px; color: #606266; }

.tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
.bad { color: #f56c6c; font-weight: 600; }
.muted { color: #a8abb2; }
.warn2 { color: #e6a23c; font-weight: 600; }

.box {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 14px;
}
.box-title { font-weight: 600; margin-bottom: 10px; font-size: 13px; }
.pairs div {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
  border-bottom: 1px dashed #f2f3f5;
}
.pairs div:last-child { border-bottom: none; }
.pairs span { color: #606266; }
.box-tip { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.6; }

/* 小节里的补充标题（表格前的说明），比 h2 轻、比 tip 重 */
.sub-title {
  margin: 14px 0 8px;
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
}

.sheet-foot {
  margin-top: 28px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
  font-size: 11px;
  color: #a8abb2;
  line-height: 1.7;
}

@media (max-width: 1280px) {
  .kpis { grid-template-columns: repeat(3, 1fr); }
}
</style>

<!-- 打印：只在纸上发生。这里不能加 scoped，aside/header 属于 App.vue，
     scoped 的选择器加不上父组件的 data 属性，样式会静默失效 -->
<style>
@media print {
  .aside,
  .header,
  .no-print {
    display: none !important;
  }
  .el-main {
    padding: 0 !important;
    overflow: visible !important;
  }
  .el-container,
  .layout {
    display: block !important;
    height: auto !important;
  }
  .sheet {
    box-shadow: none !important;
    padding: 0 !important;
    max-width: none !important;
  }
  .kpis {
    grid-template-columns: repeat(6, 1fr) !important;
  }
  h2 {
    page-break-after: avoid;
  }
  .el-table {
    page-break-inside: auto;
  }
}
</style>
