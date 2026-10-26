<template>
  <div v-loading="loading" class="inspectors">
    <div class="toolbar">
      <el-page-header content="检验员分析" @back="$router.back()" />
      <div class="spacer" />
      <el-radio-group v-model="preset" @change="applyPreset">
        <el-radio-button value="30">近 30 天</el-radio-button>
        <el-radio-button value="90">近 90 天</el-radio-button>
        <el-radio-button value="365">近一年</el-radio-button>
      </el-radio-group>
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始"
        end-placeholder="结束"
        :clearable="false"
        style="width: 250px"
        @change="preset = ''; load()"
      />
    </div>

    <!-- 这一页最容易被误读成绩效排名，把定位写死在页面上，而不是只写在文档里 -->
    <el-alert type="info" :closable="false" class="positioning">
      <template #title>
        这一页看的是<b>记录本身站不站得住</b>，不是给检验员排名。
      </template>
      按不良率排座次的结果是所有的人都学会少判 NG，数据立刻好看、也立刻没用。这里的提示只说明
      <b>「值得抽一单复核」</b>：零不良可能是产品真的好，录入过快也可能是单子小。抽查是复核记录，不是查人。
    </el-alert>

    <el-alert
      v-if="ready && !rows.length"
      type="info"
      :closable="false"
      title="这段时间没有已提交的检验单。草稿与已作废的单不计入——它们不代表任何检验结论。"
    />

    <template v-if="ready && rows.length">
      <div class="summary">{{ data.summary }}</div>

      <div class="cards">
        <div class="card">
          <div class="card-label">检验员</div>
          <div class="card-value">{{ data.staff }}</div>
          <div class="card-sub">区间 {{ data.from }} ~ {{ data.to }}（{{ data.days }} 天）</div>
        </div>
        <div class="card">
          <div class="card-label">已提交单据</div>
          <div class="card-value">{{ num(data.total.orders) }}</div>
          <div class="card-sub">受检 {{ num(data.total.pieceTotal) }} 件</div>
        </div>
        <div class="card">
          <div class="card-label">同组件级不良率</div>
          <div class="card-value">{{ pct(data.baselineNgRate) }}</div>
          <div class="card-sub">组间对比的基线，含本人</div>
        </div>
        <div class="card" :class="suspects ? 'warn' : ''">
          <div class="card-label">建议复核</div>
          <div class="card-value">{{ suspects }}</div>
          <div class="card-sub">人（零不良 / 录入过快）</div>
        </div>
      </div>

      <el-table :data="rows" size="small" border :row-class-name="rowClass">
        <el-table-column prop="inspector" label="检验员" min-width="120" fixed />
        <el-table-column prop="orders" label="单数" width="70" align="right" />
        <el-table-column prop="pieceTotal" label="受检件" width="85" align="right" />
        <el-table-column prop="pieceFail" label="不良件" width="80" align="right">
          <template #default="{ row }">
            <span :class="num(row.pieceFail) > 0 ? 'bad' : ''">{{ num(row.pieceFail) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="件级不良率" width="105" align="right">
          <template #default="{ row }">{{ pct(row.ngRate) }}</template>
        </el-table-column>
        <el-table-column label="单级合格率" width="105" align="right">
          <template #default="{ row }">{{ pct(row.orderPassRate) }}</template>
        </el-table-column>
        <el-table-column prop="ngCount" label="不良项" width="80" align="right" />
        <el-table-column prop="criticalNg" label="关键NG" width="80" align="right">
          <template #default="{ row }">
            <span :class="num(row.criticalNg) > 0 ? 'bad' : ''">{{ num(row.criticalNg) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="recheckOrders" label="复检单" width="80" align="right" />
        <el-table-column prop="rowTotal" label="录入行数" width="95" align="right" />
        <el-table-column label="秒/行" width="85" align="right">
          <template #default="{ row }">{{ dec(row.secPerRow) }}</template>
        </el-table-column>
        <el-table-column label="分/单" width="80" align="right">
          <template #default="{ row }">{{ dec(row.minutesPerOrder) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)" size="small" effect="plain">{{ levelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提示" min-width="280">
          <template #default="{ row }">
            <div v-if="!row.flags || !row.flags.length" class="ok-text">—</div>
            <div v-for="(f, i) in row.flags" :key="i" class="flag">{{ f }}</div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 合计单独一块：合计行的「每行秒数」要按总量重算，不能把各行平均 -->
      <div class="total-line">
        合计：<b>{{ num(data.total.orders) }}</b> 单 ·
        受检 <b>{{ num(data.total.pieceTotal) }}</b> 件 ·
        不良 <b>{{ num(data.total.pieceFail) }}</b> 件 ·
        件级不良率 <b>{{ pct(data.total.ngRate) }}</b> ·
        平均每行 <b>{{ dec(data.total.secPerRow) }}</b> 秒 ·
        平均每单 <b>{{ dec(data.total.minutesPerOrder) }}</b> 分钟
      </div>

      <div class="rules">
        <div class="rules-title">提示是怎么算出来的</div>
        <ul>
          <li><b>零不良</b>：同组已有人判出不良，而这个人 10 单以上、一处不良都没有。单量不到 10 不提示——偶然而已。</li>
          <li><b>录入过快</b>：录入行数 ≥ 30 且平均每行不到 2 秒。只对「过快」提示，「慢」不提示：现场常常早上开单、下班前补录，时长本身不是证据。</li>
          <li><b>不良率偏离</b>：不足同组的三分之一，或高于两倍且不良件 ≥ 3。偏低可能是放水，偏高可能是手法/量具差异，也可能是判得过细——数字分不出来，要看单。</li>
          <li>分母为 0 的比率显示「-」而不是 0%：一件都还没检，和全都不合格不是一回事。</li>
        </ul>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api/http'

const loading = ref(false)
const ready = ref(false)
const data = ref({})
const rows = computed(() => data.value.rows || [])
const suspects = computed(() => rows.value.filter(r => r.level === 'SUSPECT').length)

const preset = ref('90')
const range = ref([])

/** yyyy-MM-dd，用本地时区拼：toISOString() 在 UTC+8 会把日期往前挪一天 */
function ymd(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function applyPreset() {
  const days = Number(preset.value)
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - days + 1)
  range.value = [ymd(start), ymd(end)]
  load()
}

async function load() {
  if (!range.value || range.value.length !== 2) return
  loading.value = true
  try {
    data.value = await http.get('/stat/inspectors', {
      params: { from: range.value[0], to: range.value[1] }
    })
    ready.value = true
  } finally {
    loading.value = false
  }
}

const num = v => (v === null || v === undefined ? 0 : Number(v))
const dec = v => (v === null || v === undefined ? '-' : Number(v))
const pct = v => (v === null || v === undefined ? '-' : (Number(v) * 100).toFixed(1) + '%')

const levelText = l => ({ SUSPECT: '建议复核', WATCH: '可关注', OK: '正常' }[l] || '正常')
const levelType = l => ({ SUSPECT: 'danger', WATCH: 'warning', OK: 'success' }[l] || 'info')
const rowClass = ({ row }) => (row.level === 'SUSPECT' ? 'row-suspect' : '')

onMounted(applyPreset)
</script>

<style scoped>
.inspectors { padding: 0 4px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.spacer { flex: 1; }
.positioning { margin-bottom: 12px; line-height: 1.7; }
.summary {
  background: #f5f7fa;
  border-left: 3px solid var(--el-color-primary);
  padding: 10px 14px;
  margin-bottom: 12px;
  line-height: 1.7;
  color: #303133;
}
.cards { display: flex; gap: 12px; margin-bottom: 12px; }
.card {
  flex: 1;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px 14px;
  background: #fff;
}
.card.warn { border-color: var(--el-color-warning); background: #fdf6ec; }
.card-label { font-size: 12px; color: #909399; }
.card-value { font-size: 24px; font-weight: 500; margin: 4px 0; color: #303133; }
.card-sub { font-size: 12px; color: #909399; }
.bad { color: var(--el-color-danger); font-weight: 500; }
.ok-text { color: #c0c4cc; }
.flag { font-size: 12px; line-height: 1.6; color: var(--el-color-warning); }
:deep(.row-suspect) { background: #fff8f8; }
.total-line {
  margin-top: 10px;
  padding: 10px 14px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  color: #303133;
}
.rules {
  margin-top: 16px;
  padding: 12px 16px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  line-height: 1.9;
}
.rules-title { font-weight: 500; margin-bottom: 4px; color: #303133; }
.rules ul { margin: 0; padding-left: 18px; }
</style>
