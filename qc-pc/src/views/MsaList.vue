<template>
  <el-card>
    <div class="filters">
      <el-input
        v-model="filter.keyword"
        placeholder="研究号 / 量具编号 / 特性"
        clearable
        style="width: 240px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filter.verdict" placeholder="全部结论" clearable style="width: 190px" @change="reload">
        <el-option label="可接受（≤ 10%）" value="GOOD" />
        <el-option label="有条件接受（10~30%）" value="MARGINAL" />
        <el-option label="不可接受（> 30%）" value="BAD" />
      </el-select>
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="router.push('/msa/new')">做一次 GR&amp;R</el-button>
    </div>

    <!-- 一句话把这件事说清楚。很多人以为「量具校准合格」就等于「数据可信」，
         这是质检里最常见的误解，也是最贵的那个 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="校准合格 ≠ 能测出这道公差"
      description="校准只管「这把尺和标准器对得上」。GR&R 研究的是：同一个人反复测、不同的人测同一件，读数能差多少。差得比零件本身的差异还大，判合格与不合格就靠运气。%GRR ≤ 10% 可接受，10~30% 有条件接受，> 30% 不可接受。"
      style="margin-bottom: 14px"
    />

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="studyNo" label="研究号" width="160" />
      <el-table-column label="量具" width="150">
        <template #default="{ row }">
          <div>{{ row.gaugeCode }}</div>
          <div class="sub">{{ row.gaugeName || '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="特性" min-width="150">
        <template #default="{ row }">
          <div>{{ row.characteristic }}</div>
          <div class="sub">{{ specText(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="结构" width="100">
        <template #default="{ row }">{{ row.appraiserCount }} 人 × {{ row.partCount }} 件 × {{ row.trialCount }} 次</template>
      </el-table-column>
      <el-table-column label="%GRR" width="90" align="right">
        <template #default="{ row }">
          <b :class="tone(row)">{{ row.result ? row.result.pctGrr + '%' : '—' }}</b>
        </template>
      </el-table-column>
      <el-table-column label="ndc" width="70" align="right">
        <template #default="{ row }">
          <span :class="row.result && row.result.ndc < 5 ? 'bad' : ''">{{ row.result ? row.result.ndc : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结论" width="115">
        <template #default="{ row }">
          <el-tag v-if="row.result" size="small" :type="tagType(row.result.verdict)">
            {{ gradeLabel(row.result.verdict) }}
          </el-tag>
          <span v-else class="sub">算不出</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="录入人" width="95" />
      <el-table-column label="日期" width="150">
        <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">看明细</el-button>
          <el-button v-if="canCancel(row)" link type="danger" @click="doCancel(row)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page.current"
      v-model:page-size="page.size"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 14px"
      @size-change="load"
      @current-change="load"
    />
  </el-card>

  <el-drawer v-model="drawer" size="760px" :title="detail ? detail.studyNo : ''">
    <template v-if="detail">
      <div class="head">
        <div class="big" :class="tone(detail)">{{ detail.result ? detail.result.pctGrr + '%' : '—' }}</div>
        <div class="head-right">
          <el-tag :type="detail.result ? tagType(detail.result.verdict) : 'info'">
            {{ detail.result ? gradeLabel(detail.result.verdict) : '算不出' }}
          </el-tag>
          <div class="sub" style="margin-top: 6px">%GRR（过程变差口径）</div>
        </div>
      </div>

      <el-alert
        v-if="detail.result"
        :type="alertType(detail.result.verdict)"
        :closable="false"
        :title="detail.result.verdictText"
        style="margin-bottom: 14px"
      />

      <el-descriptions :column="3" border size="small" style="margin-bottom: 14px">
        <el-descriptions-item label="量具">{{ detail.gaugeCode }} {{ detail.gaugeName || '' }}</el-descriptions-item>
        <el-descriptions-item label="特性">{{ detail.characteristic }} {{ detail.unit || '' }}</el-descriptions-item>
        <el-descriptions-item label="规格">
          {{ detail.lsl != null && detail.usl != null ? detail.lsl + ' ~ ' + detail.usl : '没填' }}
        </el-descriptions-item>
        <el-descriptions-item label="评价人">{{ (detail.appraisers || []).join(' / ') }}</el-descriptions-item>
        <el-descriptions-item label="结构">
          {{ detail.appraiserCount }} 人 × {{ detail.partCount }} 件 × {{ detail.trialCount }} 次
        </el-descriptions-item>
        <el-descriptions-item label="产品">{{ detail.productName || '—' }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="detail.result">
        <div class="sec">变差分解</div>
        <table class="kv">
          <thead>
            <tr>
              <th>项目</th>
              <th>变差（6σ）</th>
              <th>占过程变差</th>
              <th>什么意思</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>EV 重复性</td>
              <td>{{ detail.result.ev }}</td>
              <td>{{ detail.result.pctEv }}%</td>
              <td>同一个人反复测同一件，差多少</td>
            </tr>
            <tr>
              <td>AV 再现性</td>
              <td>{{ detail.result.av }}</td>
              <td>{{ detail.result.pctAv }}%</td>
              <td>不同的人测同一件，差多少</td>
            </tr>
            <tr class="strong">
              <td>GRR</td>
              <td>{{ detail.result.grr }}</td>
              <td>{{ detail.result.pctGrr }}%</td>
              <td>上面两项合起来，就是「测量系统带来的噪声」</td>
            </tr>
            <tr>
              <td>PV 零件变差</td>
              <td>{{ detail.result.pv }}</td>
              <td>{{ detail.result.pctPv }}%</td>
              <td>零件之间真实的差异，这才是我们想看到的</td>
            </tr>
            <tr>
              <td>TV 总变差</td>
              <td>{{ detail.result.tv }}</td>
              <td>100%</td>
              <td>GRR 与 PV 的合成</td>
            </tr>
          </tbody>
        </table>

        <div class="sec">判定依据</div>
        <table class="kv">
          <tbody>
            <tr>
              <td>ndc 可区分类别数</td>
              <td>
                <b :class="detail.result.ndc < 5 ? 'bad' : ''">{{ detail.result.ndc }}</b>
                <span class="sub">（AIAG 要求 ≥ 5）</span>
              </td>
            </tr>
            <tr>
              <td>按公差算的 %GRR</td>
              <td>
                <template v-if="detail.result.pctGrrTol != null">
                  {{ detail.result.pctGrrTol }}%
                  <span class="sub">（公差带 {{ (detail.usl - detail.lsl).toFixed(3) }} 上摊多少）</span>
                </template>
                <span v-else class="sub">没填上下限，算不出来</span>
              </td>
            </tr>
          </tbody>
        </table>

        <template v-if="(detail.result.notes || []).length">
          <div class="sec">要注意的地方</div>
          <ul class="notes">
            <li v-for="(n, i) in detail.result.notes" :key="i">{{ n }}</li>
          </ul>
        </template>

        <div class="sec">各评价人</div>
        <table class="kv">
          <thead>
            <tr>
              <th>评价人</th>
              <th>总均值</th>
              <th>平均极差 R̄</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(name, i) in detail.appraisers" :key="i">
              <td>{{ name }}</td>
              <td>{{ detail.result.appraiserMean[i] }}</td>
              <td>{{ detail.result.appraiserRange[i] }}</td>
            </tr>
          </tbody>
        </table>

        <div class="sec">各零件</div>
        <table class="kv">
          <thead>
            <tr>
              <th>零件</th>
              <th>均值</th>
              <th>件内差（不同人测同一件的差）</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(m, j) in detail.result.partMean" :key="j">
              <td>{{ j + 1 }}</td>
              <td>{{ m }}</td>
              <td>{{ detail.result.partRange[j] }}</td>
            </tr>
          </tbody>
        </table>

        <div class="sec">原始读数</div>
        <div v-for="(parts, i) in detail.readings" :key="i" class="reading-block">
          <div class="reading-head">{{ detail.appraisers[i] }}</div>
          <table class="kv">
            <thead>
              <tr>
                <th>零件</th>
                <th v-for="k in detail.trialCount" :key="k">第 {{ k }} 次</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(trials, j) in parts" :key="j">
                <td>{{ j + 1 }}</td>
                <td v-for="(v, k) in trials" :key="k">{{ v }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <div v-else class="sub" style="padding: 20px 0">这张单的读数读不出来了，结论留空。原始记录在库里，可以找管理员捞。</div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getStoredUser } from '../api/http'
import { gradeLabel } from '../utils/grr'

const router = useRouter()
const user = getStoredUser() || {}

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const page = reactive({ current: 1, size: 10 })
const filter = reactive({ keyword: '', verdict: '' })

const drawer = ref(false)
const detail = ref(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await http.get('/msa/page', {
      params: {
        current: page.current,
        size: page.size,
        keyword: filter.keyword.trim() || undefined,
        verdict: filter.verdict || undefined
      }
    })
    rows.value = res.records || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  page.current = 1
  load()
}

function resetFilters() {
  filter.keyword = ''
  filter.verdict = ''
  reload()
}

async function openDetail(row) {
  drawer.value = true
  detail.value = await http.get(`/msa/${row.id}`)
}

/** 作废只对管理员或录入人本人开放——和后端 MsaService.cancel 同一口径 */
const canCancel = (row) => user.role === 'ADMIN' || row.createdBy === displayName()

function displayName() {
  return user.realName || user.username || ''
}

async function doCancel(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      '这张单会从量具履历里消失，写一句为什么。',
      `作废 ${row.studyNo}`,
      { inputPlaceholder: '比如：读数录错了一列，重做中', inputValidator: (v) => (v && v.trim().length >= 2) || '理由至少两个字' }
    )
    await http.post(`/msa/${row.id}/cancel`, { reason: value })
    ElMessage.success('已作废')
    load()
  } catch (e) {
    if (e !== 'cancel') throw e
  }
}

const tagType = (v) => ({ GOOD: 'success', MARGINAL: 'warning', BAD: 'danger' }[v] || 'info')
const alertType = (v) => ({ GOOD: 'success', MARGINAL: 'warning', BAD: 'error' }[v] || 'info')
const toneOf = (v) => ({ GOOD: 'good', MARGINAL: 'warn', BAD: 'bad' }[v] || '')

const tone = (row) => (row && row.result ? toneOf(row.result.verdict) : '')

function specText(row) {
  if (row.lsl == null || row.usl == null) return row.unit ? row.unit : '没填规格'
  return `${row.lsl} ~ ${row.usl}${row.unit ? ' ' + row.unit : ''}`
}
</script>

<style scoped>
.filters {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.spacer {
  flex: 1;
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.good {
  color: #67c23a;
}
.warn {
  color: #e6a23c;
}
.bad {
  color: #f56c6c;
}
.head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
}
.big {
  font-size: 40px;
  font-weight: 600;
  line-height: 1;
}
.head-right {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.sec {
  font-weight: 600;
  margin: 18px 0 8px;
  font-size: 14px;
}
.kv {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.kv th,
.kv td {
  border: 1px solid var(--el-border-color-lighter);
  padding: 6px 10px;
  text-align: left;
}
.kv th {
  background: var(--el-fill-color-lighter);
  font-weight: 500;
}
.kv tr.strong td {
  font-weight: 600;
  background: var(--el-fill-color-lighter);
}
.notes {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
}
.reading-block {
  margin-bottom: 12px;
}
.reading-head {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}
</style>
